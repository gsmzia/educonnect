from fastapi import FastAPI, Depends, HTTPException, status, UploadFile, File, Form, BackgroundTasks, Request, Header, Query
from fastapi.staticfiles import StaticFiles
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError
from sqlalchemy.exc import SQLAlchemyError
from pydantic import BaseModel, EmailStr
from typing import List, Optional
from collections import deque
import datetime
import time
import smtplib
import random
import os
import shutil
import threading
from email.message import EmailMessage
from passlib.context import CryptContext

import models
from database import engine, get_db
from auth import issue_access_token, get_current_user
from cloudinary_service import (
    save_and_upload_file, save_and_upload_path,
    save_and_upload_file_asset, save_and_upload_path_asset, destroy_asset,
)
from fcm_service import send_personal_fcm_push, send_group_fcm_push

# Existing deployments stored passwords as plaintext.  New passwords are hashed
# with bcrypt and a successful legacy login upgrades that one account in place;
# no account reset or bulk rewrite is needed.
password_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def _is_password_hash(value: str | None) -> bool:
    return bool(value and value.startswith(("$2a$", "$2b$", "$2y$")))


def _verify_password(plain_password: str, stored_password: str | None) -> bool:
    if not stored_password:
        return False
    if _is_password_hash(stored_password):
        try:
            return password_context.verify(plain_password, stored_password)
        except ValueError:
            return False
    # Constant-time comparison protects the short migration window for legacy
    # plaintext rows.  They are upgraded only after a successful login.
    return __import__("hmac").compare_digest(plain_password, stored_password)

models.Base.metadata.create_all(bind=engine)

# Auto-migrate schema for newly added columns if they don't exist yet (works on SQLite & PostgreSQL)
def _auto_migrate():
    is_sqlite = "sqlite" in engine.dialect.name.lower()
    bool_false = "0" if is_sqlite else "FALSE"
    timestamp_type = "DATETIME" if is_sqlite else "TIMESTAMP"
    
    with engine.connect() as conn:
        from sqlalchemy import text
        columns_to_add = [
            ("chat_messages", "message_type", "VARCHAR DEFAULT 'text'"),
            ("chat_messages", "media_url", "VARCHAR"),
            ("chat_messages", "media_public_id", "VARCHAR"),
            ("chat_messages", "media_resource_type", "VARCHAR"),
            ("chat_messages", "is_media_expired", f"BOOLEAN DEFAULT {bool_false}"),
            ("chat_messages", "media_downloaded_at", timestamp_type),
            ("chat_messages", "is_delivered", f"BOOLEAN DEFAULT {bool_false}"),
            ("chat_messages", "is_read", f"BOOLEAN DEFAULT {bool_false}"),
            ("chat_messages", "is_deleted", f"BOOLEAN DEFAULT {bool_false}"),
            ("chat_messages", "deleted_by", "INTEGER"),
            ("chat_messages", "is_pinned", f"BOOLEAN DEFAULT {bool_false}"),
            ("chat_messages", "pinned_at", timestamp_type),
            ("chat_messages", "idempotency_key", "VARCHAR"),
            ("chat_messages", "duration_sec", "INTEGER DEFAULT 0"),
            ("personal_messages", "message_type", "VARCHAR DEFAULT 'text'"),
            ("personal_messages", "media_url", "VARCHAR"),
            ("personal_messages", "media_public_id", "VARCHAR"),
            ("personal_messages", "media_resource_type", "VARCHAR"),
            ("personal_messages", "is_media_expired", f"BOOLEAN DEFAULT {bool_false}"),
            ("personal_messages", "media_downloaded_at", timestamp_type),
            ("personal_messages", "is_delivered", f"BOOLEAN DEFAULT {bool_false}"),
            ("personal_messages", "is_read", f"BOOLEAN DEFAULT {bool_false}"),
            ("personal_messages", "is_deleted", f"BOOLEAN DEFAULT {bool_false}"),
            ("personal_messages", "deleted_by", "INTEGER"),
            ("personal_messages", "is_pinned", f"BOOLEAN DEFAULT {bool_false}"),
            ("personal_messages", "pinned_at", timestamp_type),
            ("personal_messages", "idempotency_key", "VARCHAR"),
            ("personal_messages", "duration_sec", "INTEGER DEFAULT 0"),
            ("group_students", "is_left", f"BOOLEAN DEFAULT {bool_false}"),
            ("group_students", "left_at", timestamp_type),
            ("groups", "profile_pic", "VARCHAR"),
            ("groups", "degree", "VARCHAR"),
            ("groups", "major", "VARCHAR"),
            ("users", "degree", "VARCHAR"),
            ("users", "major", "VARCHAR"),
            ("users", "academic_year", "VARCHAR"),
            ("users", "profile_pic", "VARCHAR"),
            ("assignments", "submission_date", timestamp_type),
            ("assignments", "assignment_type", "VARCHAR DEFAULT 'Individual'"),
            ("assignments", "group_size", "INTEGER"),
            ("assignments", "group_members", "VARCHAR"),
            ("post_comments", "parent_id", "INTEGER"),
            ("posts", "target_audience", "VARCHAR DEFAULT 'ACADEMIC'"),
            ("posts", "idempotency_key", "VARCHAR"),
            ("resumable_uploads", "media_url", "VARCHAR"),
            ("users", "university_id", "INTEGER"),
            ("users", "is_founder", f"BOOLEAN DEFAULT {bool_false}"),
            ("users", "semester", "INTEGER"),
            ("users", "active_session_mode", "VARCHAR DEFAULT 'academic'"),
            ("groups", "university_id", "INTEGER"),
            ("groups", "academic_subject_id", "INTEGER"),
            ("groups", "academic_semester_id", "INTEGER"),
            ("universities", "founder_email", "VARCHAR"),
            ("universities", "founder_otp", "VARCHAR"),
            ("universities", "founder_otp_expires_at", "TIMESTAMP"),
            ("universities", "status", "VARCHAR DEFAULT 'pending_email_verification'"),
        ]

        for table, column, col_type in columns_to_add:
            try:
                if is_sqlite:
                    conn.execute(text(f"ALTER TABLE {table} ADD COLUMN {column} {col_type}"))
                else:
                    conn.execute(text(f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS {column} {col_type}"))
                conn.commit()
            except Exception:
                pass

        try:
            # A native Simple User account is always simple.  Existing academic
            # accounts retain academic mode until they explicitly switch.
            conn.execute(text(
                "UPDATE users SET active_session_mode = 'simple' "
                "WHERE LOWER(role) IN ('simple user', 'simple_user', 'simpleuser', 'user') "
                "AND (active_session_mode IS NULL OR LOWER(active_session_mode) <> 'simple')"
            ))
            conn.execute(text(
                "UPDATE users SET active_session_mode = 'academic' "
                "WHERE active_session_mode IS NULL OR LOWER(active_session_mode) NOT IN ('academic', 'simple')"
            ))
            conn.commit()
        except Exception:
            conn.rollback()

        try:
            # Backfill target_audience for any existing Simple User posts
            conn.execute(text(
                "UPDATE posts SET target_audience = 'SIMPLE' "
                "WHERE author_id IN (SELECT id FROM users WHERE LOWER(role) IN ('simple user', 'simple_user', 'simpleuser', 'user'))"
            ))
            conn.commit()
        except Exception:
            pass

        # Create unique indexes for message deduplication.  Post idempotency is
        # handled separately below so an old chat-table issue cannot weaken it.
        try:
            conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_msg_idempotency ON chat_messages (idempotency_key) WHERE idempotency_key IS NOT NULL"))
            conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uq_personal_msg_idempotency ON personal_messages (idempotency_key) WHERE idempotency_key IS NOT NULL"))
            conn.commit()
        except Exception:
            conn.rollback()
            try:
                conn.execute(text("CREATE INDEX IF NOT EXISTS idx_chat_msg_idempotency ON chat_messages (idempotency_key)"))
                conn.execute(text("CREATE INDEX IF NOT EXISTS idx_personal_msg_idempotency ON personal_messages (idempotency_key)"))
                conn.commit()
            except Exception:
                conn.rollback()

        # A SELECT-before-INSERT check is inherently racy: two requests can both
        # observe no row and then insert.  Enforce the post key in the database.
        # If a pre-existing database already contains duplicate legacy keys, keep
        # every post and retain the earliest key as the retry target.  The later
        # duplicate rows receive a distinct archival key before the unique index
        # is added; no post, media, or user content is deleted.
        try:
            from sqlalchemy import inspect

            inspector = inspect(conn)
            has_unique_post_key = any(
                index.get("unique") and index.get("column_names") == ["idempotency_key"]
                for index in inspector.get_indexes("posts")
            ) or any(
                constraint.get("column_names") == ["idempotency_key"]
                for constraint in inspector.get_unique_constraints("posts")
            )

            if not has_unique_post_key:
                duplicate_rows = conn.execute(text("""
                    SELECT id, idempotency_key
                    FROM posts
                    WHERE idempotency_key IS NOT NULL
                      AND id NOT IN (
                          SELECT MIN(id)
                          FROM posts
                          WHERE idempotency_key IS NOT NULL
                          GROUP BY idempotency_key
                      )
                """)).mappings().all()
                if duplicate_rows:
                    conn.execute(
                        text("UPDATE posts SET idempotency_key = :replacement WHERE id = :id"),
                        [
                            {
                                "id": row["id"],
                                "replacement": f"{row['idempotency_key']}__legacy_duplicate__{row['id']}"
                            }
                            for row in duplicate_rows
                        ]
                    )
                    conn.commit()
                    print(
                        f"Migrated {len(duplicate_rows)} duplicate post idempotency key(s) "
                        "without deleting any posts."
                    )

                conn.execute(text(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_post_idempotency "
                    "ON posts (idempotency_key) WHERE idempotency_key IS NOT NULL"
                ))
                conn.commit()
        except Exception as exc:
            conn.rollback()
            # Do not start in a state where retry requests can silently duplicate posts.
            raise RuntimeError("Could not enforce unique post idempotency keys") from exc

        # Academic hierarchy integrity.  New databases receive these constraints
        # from models.py; this migration adds equivalent partial indexes to an
        # existing database without touching any user rows.  If an old database
        # already has duplicate memberships, do not delete/merge data silently:
        # report it for a reviewed migration and retain the route-level locks.
        try:
            duplicate_membership = conn.execute(text("""
                SELECT 1 FROM group_students
                GROUP BY group_id, student_id
                HAVING COUNT(*) > 1
                LIMIT 1
            """)).first()
            if duplicate_membership:
                print(
                    "WARNING: group_students has legacy duplicate memberships; "
                    "uq_group_student_membership was not created. Review and "
                    "merge those rows before enabling the database constraint."
                )
            else:
                conn.execute(text(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_group_student_membership "
                    "ON group_students (group_id, student_id)"
                ))
                conn.commit()

            duplicate_official_group = conn.execute(text("""
                SELECT 1 FROM groups
                WHERE university_id IS NOT NULL AND academic_subject_id IS NOT NULL
                GROUP BY university_id, academic_subject_id
                HAVING COUNT(*) > 1
                LIMIT 1
            """)).first()
            if duplicate_official_group:
                print(
                    "WARNING: groups has duplicate official subject groups; "
                    "uq_official_group_subject was not created. Review those "
                    "groups before enabling the database constraint."
                )
            else:
                conn.execute(text(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_official_group_subject "
                    "ON groups (university_id, academic_subject_id) "
                    "WHERE university_id IS NOT NULL AND academic_subject_id IS NOT NULL"
                ))
                conn.commit()
        except Exception:
            conn.rollback()
            # Legacy schema differences should not prevent the service from
            # starting; route-level locks continue to protect new writes.
            print("WARNING: academic uniqueness migration could not be applied.")

        # Allow multi-device per user (drop unique constraint on user_id if present)
        if not is_sqlite:
            try:
                conn.execute(text("ALTER TABLE user_fcm_tokens DROP CONSTRAINT IF EXISTS user_fcm_tokens_user_id_key"))
                conn.execute(text("ALTER TABLE user_fcm_tokens DROP CONSTRAINT IF EXISTS uq_user_fcm_tokens_user_id"))
                conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uq_user_fcm_token_val ON user_fcm_tokens (token)"))
                conn.commit()
            except Exception:
                pass

        # ── Performance indexes for the posts feed ─────────────────────────────
        # These allow the feed query (ORDER BY timestamp DESC, id DESC) and the
        # IN-clause batch fetches inside format_posts_batch to run via index
        # rather than full-table scans.  All created with IF NOT EXISTS so they
        # are safe to run on every startup against an already-indexed database.
        try:
            # Composite covering index for the feed pagination sort:
            #   ORDER BY timestamp DESC, id DESC
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_posts_ts_id "
                "ON posts (timestamp DESC, id DESC)"
            ))
            # Audience filter + sort: WHERE target_audience IN (...) ORDER BY timestamp DESC
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_posts_audience_ts "
                "ON posts (target_audience, timestamp DESC, id DESC)"
            ))
            # Author filter (My Posts tab + audience fallback):
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_posts_author_ts "
                "ON posts (author_id, timestamp DESC)"
            ))
            # Like counts: IN (post_ids) GROUP BY post_id
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_likes_post "
                "ON post_likes (post_id)"
            ))
            # Liked-by-me: WHERE user_id=? AND post_id IN (...)
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_likes_user_post "
                "ON post_likes (user_id, post_id)"
            ))
            # Save counts and saved-by-me:
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_saves_post "
                "ON post_saves (post_id)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_saves_user_post "
                "ON post_saves (user_id, post_id)"
            ))
            # Comment counts:
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_comments_post "
                "ON post_comments (post_id)"
            ))
            # Comments pagination: WHERE post_id=? AND parent_id IS NULL ORDER BY timestamp DESC
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_comments_post_toplevel_ts "
                "ON post_comments (post_id, parent_id, timestamp DESC, id DESC)"
            ))
            # Comment timestamp for keyset cursor:
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_comments_ts "
                "ON post_comments (timestamp DESC, id DESC)"
            ))
            # Replies fetch: WHERE post_id=? AND parent_id IN (...)
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_comments_parent "
                "ON post_comments (post_id, parent_id, timestamp ASC, id ASC)"
            ))
            # Poll votes batch:
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_poll_votes_post "
                "ON post_poll_votes (post_id)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_poll_votes_user_post "
                "ON post_poll_votes (user_id, post_id)"
            ))
            # Comment likes batch:
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_comment_likes_comment "
                "ON post_comment_likes (comment_id)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_post_comment_likes_user_comment "
                "ON post_comment_likes (user_id, comment_id)"
            ))
            conn.commit()
        except Exception:
            conn.rollback()  # Non-fatal — indexes are performance aids, not correctness gates.

_auto_migrate()

app = FastAPI(title="Smart Classroom System API")

from fastapi.middleware.cors import CORSMiddleware

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

from academic.routes import router as academic_router
app.include_router(academic_router, prefix="/api/academic", tags=["Academic Hierarchy"])

# In-memory buffer for real-time live request logs (like Render/Logcat)
request_logs = deque(maxlen=100)

# Per-instance sliding-window protection.  It is intentionally conservative so
# normal app traffic and resumable 1MB chunks work uninterrupted, while a bad
# client cannot consume every synchronous worker.  At multi-replica scale use a
# Redis/API-gateway limiter as the shared authority; process memory is not
# globally coordinated between replicas.
_rate_limit_lock = threading.Lock()
_rate_limit_buckets = {}
_rate_limit_last_cleanup = 0.0


def _client_rate_limit_key(request: Request) -> str:
    # X-Forwarded-For is spoofable unless the platform strips/replaces it.
    if os.environ.get("TRUST_PROXY_HEADERS", "false").strip().lower() in {"1", "true", "yes"}:
        forwarded = request.headers.get("x-forwarded-for", "").split(",")[0].strip()
        if forwarded:
            return forwarded
    return request.client.host if request.client else "unknown"


def _rate_limit_for_path(path: str) -> int:
    def configured_limit(name: str, fallback: int) -> int:
        try:
            return max(1, min(int(os.environ.get(name, fallback)), 10_000))
        except (TypeError, ValueError):
            return fallback
    if "/resumable-upload/chunk" in path:
        return configured_limit("RATE_LIMIT_UPLOAD_CHUNKS_PER_MINUTE", 180)
    if any(part in path for part in ("/login", "/register", "/forgot", "/otp")):
        return configured_limit("RATE_LIMIT_AUTH_PER_MINUTE", 20)
    return configured_limit("RATE_LIMIT_API_PER_MINUTE", 120)


def _allow_request(request: Request) -> tuple[bool, int]:
    global _rate_limit_last_cleanup
    path = request.url.path
    if path in {"/healthz", "/readyz"}:
        return True, 0
    now = time.monotonic()
    limit = max(1, _rate_limit_for_path(path))
    tier = (
        "upload" if "/resumable-upload/chunk" in path else
        "auth" if any(part in path for part in ("/login", "/register", "/forgot", "/otp")) else
        "api"
    )
    key = (_client_rate_limit_key(request), tier)
    with _rate_limit_lock:
        bucket = _rate_limit_buckets.setdefault(key, deque())
        cutoff = now - 60.0
        while bucket and bucket[0] <= cutoff:
            bucket.popleft()
        if len(bucket) >= limit:
            retry_after = max(1, int(60 - (now - bucket[0])))
            return False, retry_after
        bucket.append(now)
        # Bound memory in case an attacker rotates source addresses.
        if now - _rate_limit_last_cleanup > 300.0:
            _rate_limit_last_cleanup = now
            for old_key in list(_rate_limit_buckets):
                old_bucket = _rate_limit_buckets[old_key]
                while old_bucket and old_bucket[0] <= cutoff:
                    old_bucket.popleft()
                if not old_bucket:
                    _rate_limit_buckets.pop(old_key, None)
    return True, 0

@app.middleware("http")
async def log_requests_middleware(request: Request, call_next):
    start_time = time.time()
    allowed, retry_after = _allow_request(request)
    if not allowed:
        response = JSONResponse(
            status_code=429,
            content={"detail": "Too many requests. Please retry shortly.", "code": "rate_limited"},
            headers={"Retry-After": str(retry_after)},
        )
    else:
        response = await call_next(request)
    duration_ms = int((time.time() - start_time) * 1000)
    
    if request.url.path != "/server-logs":
        client_ip = request.client.host if request.client else "unknown"
        request_logs.append({
            "timestamp": datetime.datetime.now().strftime("%H:%M:%S"),
            "method": request.method,
            "path": request.url.path,
            "status_code": response.status_code,
            "duration_ms": duration_ms,
            "client": client_ip
        })
    return response


@app.exception_handler(SQLAlchemyError)
async def database_error_handler(_: Request, error: SQLAlchemyError):
    logger = __import__("logging").getLogger(__name__)
    logger.exception("Database request failed: %s", error)
    return JSONResponse(
        status_code=503,
        content={"detail": "Service is temporarily busy. Please retry shortly.", "code": "database_unavailable"},
        headers={"Retry-After": "5"},
    )


@app.exception_handler(Exception)
async def unhandled_error_handler(_: Request, error: Exception):
    logger = __import__("logging").getLogger(__name__)
    logger.exception("Unhandled API error: %s", error)
    return JSONResponse(
        status_code=500,
        content={"detail": "Something went wrong. Please retry shortly.", "code": "internal_error"},
    )

# Create uploads directory if it doesn't exist
UPLOAD_DIR = "uploads"
if not os.path.exists(UPLOAD_DIR):
    os.makedirs(UPLOAD_DIR)

# A single replica uses the default local directory.  Before adding replicas,
# set this to a shared durable mount/object-storage gateway path; instance-local
# files cannot safely resume when a load balancer routes the next chunk elsewhere.
RESUMABLE_UPLOAD_DIR = os.environ.get("RESUMABLE_UPLOAD_DIR", os.path.join(UPLOAD_DIR, ".resumable"))
if not os.path.exists(RESUMABLE_UPLOAD_DIR):
    os.makedirs(RESUMABLE_UPLOAD_DIR)

# Keep the product promise in one server-side place.  This is intentionally
# enforced before disk/cloud storage so a modified client cannot consume local
# storage or Cloudinary bandwidth with an unsupported large video.
MAX_VIDEO_UPLOAD_BYTES = 100 * 1024 * 1024
VIDEO_UPLOAD_TOO_LARGE_MESSAGE = "Videos over 100 MB are coming soon. Please choose a video up to 100 MB."


def _is_video_upload(upload_file: UploadFile, declared_media_type: str = "") -> bool:
    if (declared_media_type or "").strip().lower() == "video":
        return True
    if (upload_file.content_type or "").strip().lower().startswith("video/"):
        return True
    extension = os.path.splitext(upload_file.filename or "")[1].lower()
    return extension in {".mp4", ".m4v", ".mov", ".mkv", ".webm", ".3gp", ".avi"}


def _enforce_video_upload_limit(upload_file: UploadFile, declared_media_type: str = "") -> None:
    """Reject unsupported large videos without loading their body into RAM."""
    if not _is_video_upload(upload_file, declared_media_type):
        return
    try:
        stream = upload_file.file
        current_position = stream.tell()
        stream.seek(0, os.SEEK_END)
        size_bytes = stream.tell()
        stream.seek(current_position)
    except (AttributeError, OSError):
        # FastAPI's UploadFile normally exposes a seekable temporary stream.
        # If a deployment supplies a non-seekable stream, fail closed for video
        # rather than allowing an unbounded upload through this endpoint.
        raise HTTPException(status_code=413, detail="Unable to verify video size. Please choose a video up to 100 MB.")
    if size_bytes > MAX_VIDEO_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail=VIDEO_UPLOAD_TOO_LARGE_MESSAGE)

STATIC_DIR = "static"
if not os.path.exists(STATIC_DIR):
    os.makedirs(STATIC_DIR)

app.mount("/uploads", StaticFiles(directory=UPLOAD_DIR), name="uploads")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


@app.get("/healthz")
def healthz():
    """Load-balancer liveness probe: no database or external network call."""
    return {"status": "ok"}


@app.get("/readyz")
def readyz():
    """Readiness probe: only receive traffic once this replica can reach PostgreSQL."""
    try:
        from sqlalchemy import text
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
        return {"status": "ready"}
    except Exception:
        raise HTTPException(status_code=503, detail="Database is not ready")

@app.api_route("/", methods=["GET", "HEAD"])
@app.api_route("/health", methods=["GET", "HEAD"])
def health_check():
    return {
        "status": "online",
        "service": "EduConnect Backend API",
        "code": 200,
        "message": "Server is running smoothly 24/7"
    }

@app.get("/server-logs")
def get_server_logs():
    """Live traffic logger endpoint for test_api.py monitor."""
    return list(request_logs)

# ─── Pydantic Schemas ──────────────────────────────────────────────────────────

class UserCreate(BaseModel):
    email: EmailStr
    username: str          # Unique academic ID (e.g. student_bsai_102)
    name: str
    password: str
    role: str  # "Student", "Teacher"

class OTPRequest(BaseModel):
    email: EmailStr

class OTPVerify(BaseModel):
    email: EmailStr
    otp_code: str

class DegreeUpdate(BaseModel):
    degree: str
    major: Optional[str] = None
    academic_year: str

class StudentAutoJoin(BaseModel):
    student_id: int
    degree: str
    major: str
    academic_year: str

class LoginRequest(BaseModel):
    email: EmailStr
    password: str

class SubjectCreate(BaseModel):
    name: str

class GroupCreate(BaseModel):
    class_semester: str
    subject_id: int
    teacher_id: int

class ChatMessageCreate(BaseModel):
    group_id: int
    sender_id: int
    text: str
    message_type: Optional[str] = "text"
    media_url: Optional[str] = None
    idempotency_key: Optional[str] = None

class AttendanceCreate(BaseModel):
    student_id: int
    group_id: int
    date: str
    status: str

# ── New Teacher Schemas ────────────────────────────────────────────────────────

class ProgramCreate(BaseModel):
    degree: str        # "BS", "MS", "PhD"
    name: str          # "BS Computer Science"
    total_years: int   # 4

class TeacherGroupCreate(BaseModel):
    teacher_id: int
    degree: str          # "BS"
    major: str           # "BS Artificial Intelligence"
    academic_year: str   # "Year 1"
    subject_name: str    # "Physics"

class ProgramTransfer(BaseModel):
    degree: str
    major: str
    academic_year: str
    new_teacher_username: str

class PersonalMessageCreate(BaseModel):
    sender_id: int
    receiver_id: int
    text: str
    message_type: Optional[str] = "text"
    media_url: Optional[str] = None
    idempotency_key: Optional[str] = None


def _personal_block_status(db: Session, user_id: int, other_user_id: int):
    """Return the block state as seen by user_id for a one-to-one conversation."""
    blocked_by_me = db.query(models.PersonalBlock).filter(
        models.PersonalBlock.blocker_id == user_id,
        models.PersonalBlock.blocked_user_id == other_user_id
    ).first() is not None
    blocked_by_other = db.query(models.PersonalBlock).filter(
        models.PersonalBlock.blocker_id == other_user_id,
        models.PersonalBlock.blocked_user_id == user_id
    ).first() is not None
    return {
        "is_blocked": blocked_by_me or blocked_by_other,
        "is_blocked_by_me": blocked_by_me,
        "is_blocked_by_other": blocked_by_other,
    }


def _require_personal_message_allowed(db: Session, sender_id: int, receiver_id: int):
    """Server-side enforcement used by every personal send route."""
    if sender_id == receiver_id:
        raise HTTPException(status_code=400, detail="You cannot message yourself")
    if not db.query(models.User.id).filter(models.User.id == sender_id).first():
        raise HTTPException(status_code=404, detail="Sender not found")
    if not db.query(models.User.id).filter(models.User.id == receiver_id).first():
        raise HTTPException(status_code=404, detail="Receiver not found")
    if _personal_block_status(db, sender_id, receiver_id)["is_blocked"]:
        raise HTTPException(status_code=403, detail="You cannot send messages in this chat")

class ApprovalRespondRequest(BaseModel):
    user_id: int
    status: str

class LeaveGroupRequest(BaseModel):
    user_id: int

class DeleteEveryoneRequest(BaseModel):
    user_id: int

# ─── Email Function (Exact 1:1 Pixel-Perfect Design Matching Screenshot) ────────

def send_otp_email(email: str, otp: str):
    sender_email = (os.environ.get("SENDER_EMAIL") or "").strip()
    sender_password = (os.environ.get("SENDER_APP_PASSWORD") or "").strip().replace(" ", "")

    if not sender_email or not sender_password or sender_email == "your_email@gmail.com":
        print(f"[OTP SIMULATED] SENDER_EMAIL or SENDER_APP_PASSWORD not set in Render environment. Code for {email} is: {otp}")
        return

    import base64 as _b64
    from email.mime.multipart import MIMEMultipart
    from email.mime.text import MIMEText
    from email.mime.image import MIMEImage

    # Build proper MIME email with inline image (CID) - works 100% in Gmail
    outer = MIMEMultipart("related")
    outer["Subject"] = "Your EduConnect Verification Code"
    outer["From"] = f"EduConnect <{sender_email}>"
    outer["To"] = email

    # Inner alternative (plain text + HTML)
    alt = MIMEMultipart("alternative")
    outer.attach(alt)

    # Plain text part
    plain_text = f"""Hello!

Thank you for using EduConnect.
Your OTP is: {otp}

This OTP is valid for 10 minutes.
If you didn't request this, ignore this email.
"""
    alt.attach(MIMEText(plain_text, "plain"))

    # Format OTP digits with clear spacing
    spaced_otp = "&nbsp;&nbsp;".join(list(otp))

    html_content = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Your EduConnect Verification Code</title>
    <style>
        body, table, td, p, span {{ -webkit-text-size-adjust: 100%; -ms-text-size-adjust: 100%; }}
        body {{ margin: 0 !important; padding: 0 !important; width: 100% !important; background-color: #12111A; }}
    </style>
</head>
<body style="margin: 0; padding: 0; width: 100%; background-color: #12111A; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;">
    <table width="100%" border="0" cellspacing="0" cellpadding="0" style="width: 100%; background-color: #12111A; padding: 20px 0;">
        <tr>
            <td align="center" style="padding: 10px 12px;">
                <table width="100%" border="0" cellspacing="0" cellpadding="0" style="max-width: 480px; width: 100%; background-color: #1A1826; border: 1px solid #2D2842; border-radius: 24px; overflow: hidden; padding: 36px 20px; text-align: center; margin: 0 auto;">

                    <!-- Header: 3D Logo + Brand (100% Centered & Tested with ImgBB CDN) -->
                    <tr>
                        <td align="center" style="padding: 22px 15px 6px 15px; text-align: center;">
                            <table border="0" cellspacing="0" cellpadding="0" align="center" style="margin: 0 auto; text-align: center;">
                                <tr>
                                    <td valign="middle" align="center" style="padding-right: 16px; text-align: center;">
                                        <img src="https://i.ibb.co/mVhvzKMw/educonnect-logo.png" alt="" width="92" height="92" style="display: block; width: 92px; height: 92px; object-fit: contain; border: 0; margin: 0 auto;" />
                                    </td>
                                    <td valign="middle" align="left" style="text-align: left;">
                                        <div style="font-size: 26px; font-weight: 800; line-height: 28px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
                                            <span style="color: #FFFFFF;">Edu</span><span style="color: #A855F7;">Connect</span>
                                        </div>
                                        <div style="font-size: 12px; font-weight: 600; color: #94A3B8; letter-spacing: 0.5px; margin-top: 3px;">
                                            Learn &bull; Connect &bull; Achieve
                                        </div>
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>

                    <!-- Divider -->
                    <tr>
                        <td align="center" style="padding: 16px 0 22px 0;">
                            <div style="width: 110px; height: 2px; background: linear-gradient(90deg, transparent, #A855F7, transparent); border-radius: 4px; margin: 0 auto;"></div>
                        </td>
                    </tr>

                    <!-- Hello -->
                    <tr>
                        <td align="center" style="padding-bottom: 10px; text-align: center;">
                            <h2 style="margin: 0; font-size: 22px; font-weight: 700; color: #FFFFFF; text-align: center;">Hello!</h2>
                        </td>
                    </tr>

                    <!-- Description -->
                    <tr>
                        <td align="center" style="padding: 0 10px 22px 10px; text-align: center;">
                            <p style="margin: 0; font-size: 14px; color: #CBD5E1; line-height: 22px; text-align: center;">
                                Thank you for using EduConnect.<br>
                                Use the following OTP to verify your email address and complete your registration.
                            </p>
                        </td>
                    </tr>

                    <!-- OTP Box -->
                    <tr>
                        <td align="center" style="text-align: center;">
                            <table border="0" cellspacing="0" cellpadding="0" align="center" style="margin: 0 auto; background-color: #262238; border: 1.5px solid #3E355C; border-radius: 18px; width: 90%; max-width: 320px;">
                                <tr>
                                    <td align="center" style="padding: 18px 20px; text-align: center;">
                                        <span style="font-size: 34px; font-weight: 800; color: #C084FC; letter-spacing: 6px; font-family: monospace; display: inline-block;">{spaced_otp}</span>
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>

                    <!-- Validity -->
                    <tr>
                        <td align="center" style="padding-top: 14px; padding-bottom: 24px; text-align: center;">
                            <span style="font-size: 12.5px; font-weight: 600; color: #A855F7;">This OTP is valid for 10 minutes.</span>
                        </td>
                    </tr>

                    <!-- Divider -->
                    <tr>
                        <td align="center" style="padding: 0 10px;">
                            <div style="border-top: 1px solid #28243D; width: 100%; margin: 0 auto;"></div>
                        </td>
                    </tr>

                    <!-- Footer -->
                    <tr>
                        <td align="center" style="padding-top: 18px; text-align: center;">
                            <p style="margin: 0; font-size: 11.5px; color: #64748B; text-align: center;">
                                If you didn't request this code, you can safely ignore this email.
                            </p>
                            <p style="margin: 5px 0 0 0; font-size: 11px; color: #475569; text-align: center;">
                                &copy; 2026 EduConnect. All rights reserved.
                            </p>
                        </td>
                    </tr>

                </table>
            </td>
        </tr>
    </table>
</body>
</html>"""

    alt.attach(MIMEText(html_content, "html"))

    # Attach logo image inline with Content-ID (works 100% in Gmail)
    _logo_path = os.path.join(os.path.dirname(__file__), "static", "educonnect_logo.png")
    try:
        with open(_logo_path, "rb") as _img:
            logo_img = MIMEImage(_img.read(), _subtype="png")
            logo_img.add_header("Content-ID", "<educonnect_logo>")
            logo_img.add_header("Content-Disposition", "inline", filename="educonnect_logo.png")
            outer.attach(logo_img)
    except Exception as e:
        print(f"[LOGO] Could not attach logo: {e}")

    import json, urllib.request, urllib.error

    # ── Method 0: Official Google Webhook (100% Free Forever, Real Gmail, Zero Restrictions) ──
    google_webhook = (os.environ.get("GOOGLE_EMAIL_WEBHOOK") or "").strip()
    if google_webhook:
        # For webhook (HTML-only), replace cid: logo with jsDelivr CDN URL (Gmail always loads this)
        imgbb_logo = "https://i.ibb.co/mVhvzKMw/educonnect-logo.png"
        webhook_html = html_content.replace("cid:educonnect_logo", imgbb_logo)
        payload = {
            "to": email,
            "subject": "Your EduConnect Verification Code",
            "html": webhook_html
        }
        headers = {
            "Content-Type": "application/json",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        try:
            try:
                import requests
                r = requests.post(google_webhook, json=payload, headers=headers, timeout=20, allow_redirects=True)
                if r.status_code in (200, 201, 302):
                    print(f"[OTP SUCCESS] Email sent to {email} via Official Google Webhook with code: {otp}")
                    return
            except ImportError:
                pass

            # Try GET query parameters fallback if POST was rejected
            import urllib.parse
            query_params = urllib.parse.urlencode({
                "to": email,
                "subject": "Your EduConnect Verification Code",
                "otp": otp,
                "html": webhook_html
            })
            get_url = f"{google_webhook}?{query_params}" if "?" not in google_webhook else f"{google_webhook}&{query_params}"
            req_get = urllib.request.Request(get_url, headers=headers)
            with urllib.request.urlopen(req_get, timeout=20) as resp:
                print(f"[OTP SUCCESS] Email sent to {email} via Official Google Webhook (GET) with code: {otp}")
                return
        except Exception as e_g:
            print(f"[OTP GOOGLE WEBHOOK FAILED] Error: {e_g}. Fallback code: {otp}")

    # ── Method 1: Resend HTTP API (Port 443 HTTPS - Works 100% on Render Free Tier) ──
    resend_api_key = (os.environ.get("RESEND_API_KEY") or "").strip()
    if resend_api_key:
        try:
            req = urllib.request.Request(
                "https://api.resend.com/emails",
                data=json.dumps({
                    "from": "EduConnect <onboarding@resend.dev>",
                    "to": [email],
                    "subject": "Your EduConnect Verification Code",
                    "html": html_content
                }).encode("utf-8"),
                headers={
                    "Authorization": f"Bearer {resend_api_key}",
                    "Content-Type": "application/json",
                    "User-Agent": "EduConnect/1.0"
                }
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.getcode() in (200, 201):
                    print(f"[OTP SUCCESS] Email successfully sent to {email} via Resend HTTPS API with code: {otp}")
                    return
        except Exception as e_resend:
            print(f"[OTP RESEND FAILED] Resend API error: {e_resend}. Trying SMTP fallback...")

    # ── Method 2: Brevo HTTP API (Port 443 HTTPS - Works 100% on Render Free Tier) ──
    brevo_api_key = (os.environ.get("BREVO_API_KEY") or "").strip()
    if brevo_api_key:
        try:
            req = urllib.request.Request(
                "https://api.brevo.com/v3/smtp/email",
                data=json.dumps({
                    "sender": {"name": "EduConnect", "email": sender_email or "support@educonnect.com"},
                    "to": [{"email": email}],
                    "subject": "Your EduConnect Verification Code",
                    "htmlContent": html_content
                }).encode("utf-8"),
                headers={
                    "api-key": brevo_api_key,
                    "Content-Type": "application/json"
                }
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.getcode() in (200, 201):
                    print(f"[OTP SUCCESS] Email successfully sent to {email} via Brevo HTTPS API with code: {otp}")
                    return
        except Exception as e_brevo:
            print(f"[OTP BREVO FAILED] Brevo API error: {e_brevo}. Trying SMTP fallback...")

    # ── Method 3: Standard SMTP (Port 587 / 465) ──
    try:
        server = smtplib.SMTP("smtp.gmail.com", 587, timeout=20)
        server.ehlo()
        server.starttls()
        server.ehlo()
        server.login(sender_email, sender_password)
        server.sendmail(sender_email, email, outer.as_string())
        server.quit()
        print(f"[OTP SUCCESS] Email successfully sent to {email} via TLS 587 with code: {otp}")
    except Exception as e_tls:
        print(f"[OTP TLS FAILED] Could not send via port 587: {e_tls}. Retrying via SSL 465...")
        try:
            server = smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=20)
            server.login(sender_email, sender_password)
            server.sendmail(sender_email, email, outer.as_string())
            server.quit()
            print(f"[OTP SUCCESS] Email successfully sent to {email} via SSL 465 with code: {otp}")
        except Exception as e_ssl:
            print(f"[OTP ERROR] Failed to send email to {email}. Error: {e_ssl}. Fallback OTP is: {otp}")

# ─── Auth Routes ───────────────────────────────────────────────────────────────


@app.post("/request-otp")
def request_otp(req: OTPRequest, background_tasks: BackgroundTasks, db: Session = Depends(get_db)):
    email = req.email
    if db.query(models.User).filter(models.User.email == email).first():
        raise HTTPException(status_code=400, detail="Email already registered")

    otp_code = str(random.randint(100000, 999999))

    db_otp = db.query(models.OTP).filter(models.OTP.email == email).first()
    if db_otp:
        db_otp.otp_code = otp_code
    else:
        db_otp = models.OTP(email=email, otp_code=otp_code)
        db.add(db_otp)

    db.commit()
    background_tasks.add_task(send_otp_email, email, otp_code)
    return {"message": "OTP sent successfully"}

# ─── Program Ownership & Personal Chat Endpoints ───────────────────────────────
@app.get("/personal_chats/{user_id}")
def get_personal_chats(user_id: int, db: Session = Depends(get_db)):
    # Get all distinct users this user has chatted with
    messages = db.query(models.PersonalMessage).filter(
        (models.PersonalMessage.sender_id == user_id) | 
        (models.PersonalMessage.receiver_id == user_id)
    ).all()
    
    other_user_ids = set()
    for m in messages:
        if m.sender_id != user_id:
            other_user_ids.add(m.sender_id)
        if m.receiver_id != user_id:
            other_user_ids.add(m.receiver_id)
            
    users = db.query(models.User).filter(models.User.id.in_(other_user_ids)).all()
    return [{"id": u.id, "name": u.name, "username": u.username, "role": u.role, "profile_pic": u.profile_pic} for u in users]


@app.get("/personal_blocks/{user_id}/{other_user_id}")
def get_personal_block_status(user_id: int, other_user_id: int, db: Session = Depends(get_db)):
    if user_id == other_user_id:
        raise HTTPException(status_code=400, detail="Choose another user")
    if db.query(models.User.id).filter(models.User.id.in_([user_id, other_user_id])).count() != 2:
        raise HTTPException(status_code=404, detail="User not found")
    return _personal_block_status(db, user_id, other_user_id)


@app.post("/personal_blocks/{blocker_id}/{blocked_user_id}")
def block_personal_user(blocker_id: int, blocked_user_id: int, db: Session = Depends(get_db)):
    if blocker_id == blocked_user_id:
        raise HTTPException(status_code=400, detail="You cannot block yourself")
    if db.query(models.User.id).filter(models.User.id.in_([blocker_id, blocked_user_id])).count() != 2:
        raise HTTPException(status_code=404, detail="User not found")
    existing = db.query(models.PersonalBlock).filter(
        models.PersonalBlock.blocker_id == blocker_id,
        models.PersonalBlock.blocked_user_id == blocked_user_id
    ).first()
    if not existing:
        db.add(models.PersonalBlock(blocker_id=blocker_id, blocked_user_id=blocked_user_id))
        db.commit()
    return _personal_block_status(db, blocker_id, blocked_user_id)


@app.delete("/personal_blocks/{blocker_id}/{blocked_user_id}")
def unblock_personal_user(blocker_id: int, blocked_user_id: int, db: Session = Depends(get_db)):
    block = db.query(models.PersonalBlock).filter(
        models.PersonalBlock.blocker_id == blocker_id,
        models.PersonalBlock.blocked_user_id == blocked_user_id
    ).first()
    if block:
        db.delete(block)
        db.commit()
    return _personal_block_status(db, blocker_id, blocked_user_id)

@app.get("/personal_messages/{user_id}/{other_user_id}")
def get_personal_messages(
    user_id: int,
    other_user_id: int,
    before_id: Optional[int] = Query(None, ge=1),
    after_id: Optional[int] = Query(None, ge=0),
    limit: int = Query(50, ge=1, le=100),
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    # The path contains user_id for historical client compatibility, but it is
    # never authorization.  Without this check an authenticated account could
    # read any two users' direct conversation by changing URL parameters.
    if current_user.id != user_id:
        raise HTTPException(status_code=403, detail="You can only read your own personal conversations.")
    # Check if other_user is currently online (last_seen within 25 seconds)
    other_status = db.query(models.UserOnlineStatus).filter(models.UserOnlineStatus.user_id == other_user_id).first()
    if other_status and other_status.last_seen:
        import datetime
        now = datetime.datetime.utcnow()
        last = other_status.last_seen
        if last.tzinfo is not None:
            now = now.replace(tzinfo=datetime.timezone.utc)
        if (now - last).total_seconds() <= 25:
            # Other user is online -> mark messages sent by user_id to other_user_id as delivered
            delivered_count = db.query(models.PersonalMessage).filter(
                models.PersonalMessage.sender_id == user_id,
                models.PersonalMessage.receiver_id == other_user_id,
                models.PersonalMessage.is_delivered == False
            ).update({"is_delivered": True})
            if delivered_count:
                db.commit()

    message_query = db.query(models.PersonalMessage).filter(
        ((models.PersonalMessage.sender_id == user_id) & (models.PersonalMessage.receiver_id == other_user_id)) |
        ((models.PersonalMessage.sender_id == other_user_id) & (models.PersonalMessage.receiver_id == user_id))
    )
    clear_state = db.query(models.PersonalChatClearState).filter(
        models.PersonalChatClearState.user_id == current_user.id,
        models.PersonalChatClearState.other_user_id == other_user_id,
    ).first()
    if clear_state:
        message_query = message_query.filter(
            models.PersonalMessage.id > clear_state.cleared_through_message_id
        )
    if after_id is not None:
        message_query = message_query.filter(models.PersonalMessage.id > after_id)
        messages = message_query.order_by(models.PersonalMessage.id.asc()).limit(limit).all()
    elif before_id is not None:
        message_query = message_query.filter(models.PersonalMessage.id < before_id)
        messages = message_query.order_by(models.PersonalMessage.id.desc()).limit(limit).all()
        messages.reverse()
    else:
        # Fetch newest-first efficiently, then retain chronological response order.
        messages = message_query.order_by(models.PersonalMessage.id.desc()).limit(limit).all()
        messages.reverse()
    
    result = []
    for m in messages:
        ts_str = m.timestamp.isoformat() if hasattr(m.timestamp, 'isoformat') else str(m.timestamp)
        result.append({
            "id": m.id,
            "sender_id": m.sender_id,
            "receiver_id": m.receiver_id,
            "text": m.text,
            "message_type": m.message_type or "text",
            "media_url": m.media_url,
            "is_media_expired": bool(m.is_media_expired),
            "timestamp": ts_str,
            "is_read": bool(m.is_read),
            "is_delivered": bool(m.is_delivered),
            "is_deleted": bool(m.is_deleted),
            "deleted_by": m.deleted_by,
            "is_pinned": bool(m.is_pinned),
            "pinned_at": m.pinned_at.isoformat() if (m.pinned_at and hasattr(m.pinned_at, 'isoformat')) else None,
            "idempotency_key": m.idempotency_key,
            "duration_sec": m.duration_sec or 0
        })
    return result


@app.post("/personal_messages/{other_user_id}/clear")
def clear_personal_chat(
    other_user_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """Clear only the caller's view of this one direct chat.

    Deleting shared rows would erase the other person's conversation and could
    destroy an attachment they have not downloaded.  A monotonic marker gives
    WhatsApp-style clear semantics without either data-loss bug.
    """
    if other_user_id == current_user.id:
        raise HTTPException(status_code=422, detail="A personal chat requires another user.")
    if not db.query(models.User.id).filter(models.User.id == other_user_id).first():
        raise HTTPException(status_code=404, detail="User not found")
    latest = db.query(models.PersonalMessage.id).filter(
        ((models.PersonalMessage.sender_id == current_user.id) & (models.PersonalMessage.receiver_id == other_user_id)) |
        ((models.PersonalMessage.sender_id == other_user_id) & (models.PersonalMessage.receiver_id == current_user.id))
    ).order_by(models.PersonalMessage.id.desc()).first()
    cutoff = latest[0] if latest else 0
    state = db.query(models.PersonalChatClearState).filter(
        models.PersonalChatClearState.user_id == current_user.id,
        models.PersonalChatClearState.other_user_id == other_user_id,
    ).first()
    if state:
        state.cleared_through_message_id = max(state.cleared_through_message_id, cutoff)
        state.updated_at = datetime.datetime.utcnow()
    else:
        db.add(models.PersonalChatClearState(
            user_id=current_user.id,
            other_user_id=other_user_id,
            cleared_through_message_id=cutoff,
        ))
    db.commit()
    return {"message": "This chat was cleared for you", "cleared_through_message_id": cutoff}


def _asset_has_other_message_reference(db: Session, media_url: str, exclude_personal_id: int | None = None, exclude_group_id: int | None = None) -> bool:
    """Never destroy a Cloudinary asset while a forwarded/shared message uses it."""
    personal = db.query(models.PersonalMessage.id).filter(models.PersonalMessage.media_url == media_url)
    if exclude_personal_id is not None:
        personal = personal.filter(models.PersonalMessage.id != exclude_personal_id)
    if personal.first():
        return True
    group = db.query(models.ChatMessage.id).filter(models.ChatMessage.media_url == media_url)
    if exclude_group_id is not None:
        group = group.filter(models.ChatMessage.id != exclude_group_id)
    return group.first() is not None


@app.post("/personal_messages/{message_id}/media-consumed")
def consume_personal_media(
    message_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """Acknowledge a receiver's verified local save, then retire an unshared cloud asset."""
    message = db.query(models.PersonalMessage).filter(models.PersonalMessage.id == message_id).first()
    if not message:
        raise HTTPException(status_code=404, detail="Message not found")
    if message.receiver_id != current_user.id:
        raise HTTPException(status_code=403, detail="Only the receiving account can confirm this download.")
    if message.is_media_expired:
        return {"message": "Media already retired", "is_media_expired": True}

    message.media_downloaded_at = datetime.datetime.utcnow()
    # Legacy/local assets intentionally remain available: the server has no
    # trusted provider id to delete safely.  New Cloudinary assets carry it.
    if not message.media_public_id or not message.media_url:
        db.commit()
        return {"message": "Local receipt recorded", "is_media_expired": False}
    if _asset_has_other_message_reference(db, message.media_url, exclude_personal_id=message.id):
        db.commit()
        return {"message": "Receipt recorded; shared asset retained", "is_media_expired": False}
    if not destroy_asset(message.media_public_id, message.media_resource_type):
        db.commit()
        raise HTTPException(status_code=503, detail="Media cleanup will be retried later.")
    message.is_media_expired = True
    db.commit()
    return {"message": "Media receipt confirmed and cloud copy retired", "is_media_expired": True}


def purge_expired_group_media(db: Session, now: datetime.datetime | None = None) -> int:
    """Retire only unshared, known Cloudinary group assets older than seven days."""
    cutoff = (now or datetime.datetime.utcnow()) - datetime.timedelta(days=7)
    candidates = db.query(models.ChatMessage).filter(
        models.ChatMessage.timestamp < cutoff,
        models.ChatMessage.media_public_id.isnot(None),
        models.ChatMessage.is_media_expired == False,
    ).all()
    purged = 0
    for message in candidates:
        if not message.media_url or _asset_has_other_message_reference(db, message.media_url, exclude_group_id=message.id):
            continue
        if destroy_asset(message.media_public_id, message.media_resource_type):
            message.is_media_expired = True
            purged += 1
    if purged:
        db.commit()
    return purged


def purge_confirmed_personal_media(db: Session) -> int:
    """Retry cloud cleanup for receiver-confirmed direct attachments.

    A receipt is durable even if Cloudinary has a transient outage.  This makes
    deletion retryable without ever marking an asset expired before its provider
    confirms the deletion.
    """
    candidates = db.query(models.PersonalMessage).filter(
        models.PersonalMessage.media_downloaded_at.isnot(None),
        models.PersonalMessage.media_public_id.isnot(None),
        models.PersonalMessage.is_media_expired == False,
    ).all()
    purged = 0
    for message in candidates:
        if not message.media_url or _asset_has_other_message_reference(
            db, message.media_url, exclude_personal_id=message.id
        ):
            continue
        if destroy_asset(message.media_public_id, message.media_resource_type):
            message.is_media_expired = True
            purged += 1
    if purged:
        db.commit()
    return purged


@app.post("/admin/purge-expired-group-media")
def run_group_media_purge(
    secret: Optional[str] = Header(None, alias="X-Media-Purge-Secret"),
    db: Session = Depends(get_db),
):
    """Scheduler endpoint; configure Render Cron with MEDIA_PURGE_SECRET daily."""
    expected = os.environ.get("MEDIA_PURGE_SECRET", "").strip()
    if not expected or not secret or not __import__("hmac").compare_digest(secret, expected):
        raise HTTPException(status_code=403, detail="Unauthorized")
    return {
        "message": "Media lifecycle cleanup finished",
        "group_media_purged": purge_expired_group_media(db),
        "personal_media_purged": purge_confirmed_personal_media(db),
    }

@app.post("/personal_messages")
def send_personal_message(
    data: PersonalMessageCreate, 
    background_tasks: BackgroundTasks, 
    idempotency_key: Optional[str] = Header(None, alias="Idempotency-Key"),
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    if data.sender_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only send as your own account.")
    clean_key = (idempotency_key or data.idempotency_key or "").strip()
    if clean_key:
        existing = db.query(models.PersonalMessage).filter(models.PersonalMessage.idempotency_key == clean_key).first()
        if existing:
            if existing.sender_id != current_user.id:
                raise HTTPException(status_code=409, detail="Idempotency key belongs to another request.")
            return {
                "id": existing.id,
                "sender_id": existing.sender_id,
                "receiver_id": existing.receiver_id,
                "text": existing.text,
                "message_type": existing.message_type or "text",
                "media_url": existing.media_url,
                "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                "is_read": bool(existing.is_read),
                "is_delivered": bool(existing.is_delivered),
                "is_deleted": bool(existing.is_deleted),
                "deleted_by": existing.deleted_by,
                "is_pinned": bool(existing.is_pinned),
                "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                "idempotency_key": existing.idempotency_key
            }

    _require_personal_message_allowed(db, data.sender_id, data.receiver_id)

    # Check if receiver is online right now
    recv_status = db.query(models.UserOnlineStatus).filter(models.UserOnlineStatus.user_id == data.receiver_id).first()
    is_delivered = False
    if recv_status and recv_status.last_seen:
        import datetime
        now = datetime.datetime.utcnow()
        last = recv_status.last_seen
        if last.tzinfo is not None:
            now = now.replace(tzinfo=datetime.timezone.utc)
        if (now - last).total_seconds() <= 25:
            is_delivered = True

    new_msg = models.PersonalMessage(
        sender_id=data.sender_id,
        receiver_id=data.receiver_id,
        text=data.text,
        message_type=data.message_type or "text",
        media_url=data.media_url,
        is_delivered=is_delivered,
        idempotency_key=clean_key if clean_key else None
    )
    db.add(new_msg)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        if clean_key:
            existing = db.query(models.PersonalMessage).filter(models.PersonalMessage.idempotency_key == clean_key).first()
            if existing:
                return {
                    "id": existing.id, "sender_id": existing.sender_id, "receiver_id": existing.receiver_id,
                    "text": existing.text, "message_type": existing.message_type or "text", "media_url": existing.media_url,
                    "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                    "is_read": bool(existing.is_read), "is_delivered": bool(existing.is_delivered),
                    "is_deleted": bool(existing.is_deleted), "deleted_by": existing.deleted_by,
                    "is_pinned": bool(existing.is_pinned),
                    "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                    "idempotency_key": existing.idempotency_key, "duration_sec": existing.duration_sec or 0
                }
        raise
    db.refresh(new_msg)

    # Dispatch FCM Push Notification in background
    background_tasks.add_task(
        send_personal_fcm_push,
        sender_id=new_msg.sender_id,
        receiver_id=new_msg.receiver_id,
        message_id=new_msg.id,
        text=new_msg.text,
        message_type=new_msg.message_type or "text",
        media_url=new_msg.media_url or ""
    )
    
    return {
        "id": new_msg.id,
        "sender_id": new_msg.sender_id,
        "receiver_id": new_msg.receiver_id,
        "text": new_msg.text,
        "message_type": new_msg.message_type or "text",
        "media_url": new_msg.media_url,
        "timestamp": new_msg.timestamp.isoformat() if hasattr(new_msg.timestamp, 'isoformat') else str(new_msg.timestamp),
        "is_read": bool(new_msg.is_read),
        "is_delivered": bool(new_msg.is_delivered),
        "is_deleted": bool(new_msg.is_deleted),
        "deleted_by": new_msg.deleted_by,
        "is_pinned": bool(new_msg.is_pinned),
        "pinned_at": new_msg.pinned_at.isoformat() if (new_msg.pinned_at and hasattr(new_msg.pinned_at, 'isoformat')) else None,
        "idempotency_key": new_msg.idempotency_key
    }

@app.post("/personal_messages/upload")
def upload_personal_media(
    sender_id: int = Form(...),
    receiver_id: int = Form(...),
    text: str = Form(""),
    message_type: str = Form("file"),
    duration_sec: int = Form(0),
    file: UploadFile = File(...),
    idempotency_key: Optional[str] = Header(None, alias="Idempotency-Key"),
    form_idempotency_key: Optional[str] = Form(None, alias="idempotency_key"),
    background_tasks: BackgroundTasks = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    if sender_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only upload as your own account.")
    clean_key = (idempotency_key or form_idempotency_key or "").strip()
    if clean_key:
        existing = db.query(models.PersonalMessage).filter(models.PersonalMessage.idempotency_key == clean_key).first()
        if existing:
            if existing.sender_id != current_user.id:
                raise HTTPException(status_code=409, detail="Idempotency key belongs to another request.")
            return {
                "id": existing.id,
                "sender_id": existing.sender_id,
                "receiver_id": existing.receiver_id,
                "text": existing.text,
                "message_type": existing.message_type or "file",
                "media_url": existing.media_url,
                "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                "is_read": bool(existing.is_read),
                "is_delivered": bool(existing.is_delivered),
                "is_deleted": bool(existing.is_deleted),
                "deleted_by": existing.deleted_by,
                "is_pinned": bool(existing.is_pinned),
                "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                "idempotency_key": existing.idempotency_key,
                "duration_sec": existing.duration_sec or 0
            }

    _require_personal_message_allowed(db, sender_id, receiver_id)
    _enforce_video_upload_limit(file, message_type)

    try:
        media_asset = save_and_upload_file_asset(
            file, upload_dir=UPLOAD_DIR, folder="educonnect/personal_messages", media_type=message_type
        )
        media_url = media_asset.url
    except HTTPException:
        raise
    except Exception as exc:
        # Do not expose storage/provider details to the Android client.
        raise HTTPException(status_code=502, detail="Unable to upload attachment. Please try again.") from exc
    # Check if receiver is online right now
    recv_status = db.query(models.UserOnlineStatus).filter(models.UserOnlineStatus.user_id == receiver_id).first()
    is_delivered = False
    if recv_status and recv_status.last_seen:
        import datetime
        now = datetime.datetime.utcnow()
        last = recv_status.last_seen
        if last.tzinfo is not None:
            now = now.replace(tzinfo=datetime.timezone.utc)
        if (now - last).total_seconds() <= 25:
            is_delivered = True

    new_msg = models.PersonalMessage(
        sender_id=sender_id,
        receiver_id=receiver_id,
        text=text,
        message_type=message_type,
        media_url=media_url,
        media_public_id=media_asset.public_id,
        media_resource_type=media_asset.resource_type,
        is_delivered=is_delivered,
        idempotency_key=clean_key if clean_key else None,
        duration_sec=duration_sec
    )
    db.add(new_msg)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        if clean_key:
            existing = db.query(models.PersonalMessage).filter(models.PersonalMessage.idempotency_key == clean_key).first()
            if existing:
                return {
                    "id": existing.id, "sender_id": existing.sender_id, "receiver_id": existing.receiver_id,
                    "text": existing.text, "message_type": existing.message_type or "file", "media_url": existing.media_url,
                    "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                    "is_read": bool(existing.is_read), "is_delivered": bool(existing.is_delivered),
                    "is_deleted": bool(existing.is_deleted), "deleted_by": existing.deleted_by,
                    "is_pinned": bool(existing.is_pinned),
                    "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                    "idempotency_key": existing.idempotency_key, "duration_sec": existing.duration_sec or 0
                }
        raise
    db.refresh(new_msg)

    # Dispatch FCM Push Notification in background
    if background_tasks:
        background_tasks.add_task(
            send_personal_fcm_push,
            sender_id=new_msg.sender_id,
            receiver_id=new_msg.receiver_id,
            message_id=new_msg.id,
            text=new_msg.text or (f"Sent a {message_type}"),
            message_type=new_msg.message_type or "file",
            duration_sec=duration_sec,
            media_url=new_msg.media_url or ""
        )
    
    return {
        "id": new_msg.id,
        "sender_id": new_msg.sender_id,
        "receiver_id": new_msg.receiver_id,
        "text": new_msg.text,
        "message_type": new_msg.message_type or "file",
        "media_url": new_msg.media_url,
        "timestamp": new_msg.timestamp.isoformat() if hasattr(new_msg.timestamp, 'isoformat') else str(new_msg.timestamp),
        "is_read": bool(new_msg.is_read),
        "is_delivered": bool(new_msg.is_delivered),
        "is_deleted": bool(new_msg.is_deleted),
        "deleted_by": new_msg.deleted_by,
        "is_pinned": bool(new_msg.is_pinned),
        "pinned_at": new_msg.pinned_at.isoformat() if (new_msg.pinned_at and hasattr(new_msg.pinned_at, 'isoformat')) else None,
        "idempotency_key": new_msg.idempotency_key,
        "duration_sec": new_msg.duration_sec or 0
    }

# ── Forward: server-side copy (no re-upload) ────────────────────────────────

class ForwardPersonalRequest(BaseModel):
    sender_id: int
    receiver_id: int
    media_url: str
    message_type: str = "image"
    text: str = ""
    idempotency_key: Optional[str] = None

class ForwardGroupRequest(BaseModel):
    sender_id: int
    media_url: str
    message_type: str = "image"
    text: str = ""
    idempotency_key: Optional[str] = None

@app.post("/personal_messages/forward")
def forward_personal_message(
    req: ForwardPersonalRequest,
    background_tasks: BackgroundTasks = None,
    idempotency_key: Optional[str] = Header(None, alias="Idempotency-Key"),
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """
    Forward a media message to a personal chat by reusing the existing media_url.
    No re-upload needed — the Cloudinary/server URL is copied directly.
    """
    if req.sender_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only forward as your own account.")
    clean_key = (idempotency_key or req.idempotency_key or "").strip()
    if clean_key:
        existing = db.query(models.PersonalMessage).filter(models.PersonalMessage.idempotency_key == clean_key).first()
        if existing:
            if existing.sender_id != current_user.id:
                raise HTTPException(status_code=409, detail="Idempotency key belongs to another request.")
            return {
                "id": existing.id, "sender_id": existing.sender_id, "receiver_id": existing.receiver_id,
                "text": existing.text, "message_type": existing.message_type or "file", "media_url": existing.media_url,
                "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                "is_read": bool(existing.is_read), "is_delivered": bool(existing.is_delivered),
                "is_deleted": bool(existing.is_deleted), "deleted_by": existing.deleted_by,
                "is_pinned": bool(existing.is_pinned),
                "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                "idempotency_key": existing.idempotency_key, "duration_sec": existing.duration_sec or 0
            }

    _require_personal_message_allowed(db, req.sender_id, req.receiver_id)

    recv_status = db.query(models.UserOnlineStatus).filter(models.UserOnlineStatus.user_id == req.receiver_id).first()
    is_delivered = False
    if recv_status and recv_status.last_seen:
        import datetime
        now = datetime.datetime.utcnow()
        last = recv_status.last_seen
        if last.tzinfo is not None:
            now = now.replace(tzinfo=datetime.timezone.utc)
        if (now - last).total_seconds() <= 25:
            is_delivered = True

    new_msg = models.PersonalMessage(
        sender_id=req.sender_id,
        receiver_id=req.receiver_id,
        text=req.text,
        message_type=req.message_type,
        media_url=req.media_url,
        is_delivered=is_delivered,
        idempotency_key=clean_key if clean_key else None,
    )
    db.add(new_msg)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        if clean_key:
            existing = db.query(models.PersonalMessage).filter(models.PersonalMessage.idempotency_key == clean_key).first()
            if existing:
                return {
                    "id": existing.id, "sender_id": existing.sender_id, "receiver_id": existing.receiver_id,
                    "text": existing.text, "message_type": existing.message_type or "file", "media_url": existing.media_url,
                    "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                    "is_read": bool(existing.is_read), "is_delivered": bool(existing.is_delivered),
                    "is_deleted": bool(existing.is_deleted), "deleted_by": existing.deleted_by,
                    "is_pinned": bool(existing.is_pinned),
                    "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                    "idempotency_key": existing.idempotency_key, "duration_sec": existing.duration_sec or 0
                }
        raise
    db.refresh(new_msg)

    if background_tasks:
        background_tasks.add_task(
            send_personal_fcm_push,
            sender_id=new_msg.sender_id,
            receiver_id=new_msg.receiver_id,
            message_id=new_msg.id,
            text=new_msg.text or f"Sent a {req.message_type}",
            message_type=new_msg.message_type or "file",
            media_url=new_msg.media_url or ""
        )

    return {
        "id": new_msg.id,
        "sender_id": new_msg.sender_id,
        "receiver_id": new_msg.receiver_id,
        "text": new_msg.text,
        "message_type": new_msg.message_type or "file",
        "media_url": new_msg.media_url,
        "timestamp": new_msg.timestamp.isoformat() if hasattr(new_msg.timestamp, 'isoformat') else str(new_msg.timestamp),
        "is_read": bool(new_msg.is_read),
        "is_delivered": bool(new_msg.is_delivered),
        "is_deleted": bool(new_msg.is_deleted),
        "deleted_by": new_msg.deleted_by,
        "is_pinned": bool(new_msg.is_pinned),
        "pinned_at": None,
        "idempotency_key": new_msg.idempotency_key,
        "duration_sec": 0,
    }

@app.post("/groups/{group_id}/chat/forward")
def forward_group_message(
    group_id: int,
    req: ForwardGroupRequest,
    background_tasks: BackgroundTasks = None,
    idempotency_key: Optional[str] = Header(None, alias="Idempotency-Key"),
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """
    Forward a media message to a group chat by reusing the existing media_url.
    No re-upload needed — the Cloudinary/server URL is copied directly.
    """
    if req.sender_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only forward as your own account.")
    group = _require_active_mode_allows_group(
        db.query(models.Group).filter(models.Group.id == group_id).first(), current_user
    )
    _require_group_member(db, group, current_user)
    clean_key = (idempotency_key or req.idempotency_key or "").strip()
    if clean_key:
        existing = db.query(models.ChatMessage).filter(models.ChatMessage.idempotency_key == clean_key).first()
        if existing:
            if existing.sender_id != current_user.id or existing.group_id != group_id:
                raise HTTPException(status_code=409, detail="Idempotency key belongs to another request.")
            return {
                "id": existing.id, "group_id": existing.group_id, "sender_id": existing.sender_id,
                "sender_name": existing.sender_name, "text": existing.text,
                "message_type": existing.message_type or "file", "media_url": existing.media_url,
                "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                "is_read": bool(existing.is_read), "is_delivered": bool(existing.is_delivered),
                "is_deleted": bool(existing.is_deleted), "deleted_by": existing.deleted_by,
                "is_pinned": bool(existing.is_pinned),
                "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                "idempotency_key": existing.idempotency_key, "duration_sec": existing.duration_sec or 0
            }

    sender = db.query(models.User).filter(models.User.id == req.sender_id).first()

    student_links = db.query(models.GroupStudent).filter(models.GroupStudent.group_id == group_id).all()
    other_mids = {link.student_id for link in student_links if link.student_id != req.sender_id}
    if group and group.teacher_id and group.teacher_id != req.sender_id:
        other_mids.add(group.teacher_id)

    is_delivered = False
    if other_mids:
        import datetime
        now = datetime.datetime.utcnow()
        online_statuses = db.query(models.UserOnlineStatus).filter(models.UserOnlineStatus.user_id.in_(other_mids)).all()
        for st in online_statuses:
            if st.last_seen:
                last = st.last_seen
                if last.tzinfo is not None:
                    now = now.replace(tzinfo=datetime.timezone.utc)
                if (now - last).total_seconds() <= 25:
                    is_delivered = True
                    break

    chat = models.ChatMessage(
        group_id=group_id,
        sender_id=req.sender_id,
        sender_name=sender.name if sender else "Unknown",
        text=req.text,
        message_type=req.message_type,
        media_url=req.media_url,
        is_delivered=is_delivered,
        idempotency_key=clean_key if clean_key else None,
    )
    db.add(chat)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        if clean_key:
            existing = db.query(models.ChatMessage).filter(models.ChatMessage.idempotency_key == clean_key).first()
            if existing:
                return {
                    "id": existing.id, "group_id": existing.group_id, "sender_id": existing.sender_id,
                    "sender_name": existing.sender_name, "text": existing.text,
                    "message_type": existing.message_type or "file", "media_url": existing.media_url,
                    "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                    "is_read": bool(existing.is_read), "is_delivered": bool(existing.is_delivered),
                    "is_deleted": bool(existing.is_deleted), "deleted_by": existing.deleted_by,
                    "is_pinned": bool(existing.is_pinned),
                    "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                    "idempotency_key": existing.idempotency_key, "duration_sec": existing.duration_sec or 0
                }
        raise
    db.refresh(chat)

    if background_tasks:
        background_tasks.add_task(
            send_group_fcm_push,
            sender_id=chat.sender_id,
            group_id=chat.group_id,
            message_id=chat.id,
            text=chat.text or f"Sent a {req.message_type}",
            message_type=chat.message_type or "file",
            media_url=chat.media_url or ""
        )

    return {
        "id": chat.id,
        "group_id": chat.group_id,
        "sender_id": chat.sender_id,
        "sender_name": chat.sender_name,
        "text": chat.text,
        "message_type": chat.message_type or "file",
        "media_url": chat.media_url,
        "timestamp": chat.timestamp.isoformat() if hasattr(chat.timestamp, 'isoformat') else str(chat.timestamp),
        "is_read": bool(chat.is_read),
        "is_delivered": bool(chat.is_delivered),
        "is_deleted": bool(chat.is_deleted),
        "deleted_by": chat.deleted_by,
        "is_pinned": bool(chat.is_pinned),
        "pinned_at": None,
        "idempotency_key": chat.idempotency_key,
        "duration_sec": 0,
    }

@app.post("/personal_messages/{message_id}/delete_everyone")
def delete_personal_message_for_everyone(
    message_id: int,
    data: DeleteEveryoneRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    if data.user_id != current_user.id:
        raise HTTPException(status_code=403, detail="Request user does not match authenticated user")
    msg = db.query(models.PersonalMessage).filter(models.PersonalMessage.id == message_id).first()
    if not msg:
        raise HTTPException(status_code=404, detail="Message not found")
    if msg.sender_id != data.user_id:
        raise HTTPException(status_code=403, detail="Not authorized to delete this message for everyone")
    msg.is_deleted = True
    msg.deleted_by = data.user_id
    msg.text = "🚫 This message was deleted"
    msg.media_url = None
    msg.is_pinned = False
    msg.pinned_at = None
    db.commit()
    db.refresh(msg)
    return {"message": "Message deleted for everyone", "id": msg.id, "is_deleted": True}

@app.post("/personal_messages/{message_id}/pin")
def pin_personal_message(
    message_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    msg = db.query(models.PersonalMessage).filter(models.PersonalMessage.id == message_id).first()
    if not msg:
        raise HTTPException(status_code=404, detail="Message not found")
    if current_user.id not in (msg.sender_id, msg.receiver_id):
        raise HTTPException(status_code=403, detail="Not authorized to pin this message")
    import datetime
    msg.is_pinned = True
    msg.pinned_at = datetime.datetime.utcnow()
    db.commit()
    db.refresh(msg)
    return {"message": "Message pinned", "id": msg.id, "is_pinned": True}

@app.post("/personal_messages/{message_id}/unpin")
def unpin_personal_message(
    message_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    msg = db.query(models.PersonalMessage).filter(models.PersonalMessage.id == message_id).first()
    if not msg:
        raise HTTPException(status_code=404, detail="Message not found")
    if current_user.id not in (msg.sender_id, msg.receiver_id):
        raise HTTPException(status_code=403, detail="Not authorized to unpin this message")
    msg.is_pinned = False
    msg.pinned_at = None
    db.commit()
    db.refresh(msg)
    return {"message": "Message unpinned", "id": msg.id, "is_pinned": False}

@app.post("/signup")
def signup(user: UserCreate, otp_code: str, db: Session = Depends(get_db)):
    db_otp = db.query(models.OTP).filter(models.OTP.email == user.email).first()
    if not db_otp or db_otp.otp_code != otp_code:
        raise HTTPException(status_code=400, detail="Invalid OTP")

    # Check username uniqueness
    if db.query(models.User).filter(models.User.username == user.username).first():
        raise HTTPException(status_code=400, detail="Username already taken. Please choose a different username.")

    new_user = models.User(
        email=user.email,
        username=user.username,
        password=password_context.hash(user.password),
        name=user.name,
        role=user.role
    )
    db.add(new_user)
    db.delete(db_otp)
    db.commit()
    db.refresh(new_user)
    return {
        "message": "User registered successfully",
        "user": new_user,
        "access_token": issue_access_token(new_user.id),
        "token_type": "bearer",
    }

@app.post("/login")
def login(req: LoginRequest, db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.email == req.email).first()
    if not user or not _verify_password(req.password, user.password):
        raise HTTPException(status_code=400, detail="Invalid email or password")
    if not _is_password_hash(user.password):
        # Gradual, per-user migration keeps every existing account usable while
        # permanently removing its plaintext password after the next login.
        user.password = password_context.hash(req.password)
        db.commit()
        db.refresh(user)
    university_name = None
    if user.university_id:
        university_name = db.query(models.University.name).filter(
            models.University.id == user.university_id
        ).scalar()
    return {
        "message": "Login successful",
        "user": user,
        "university_name": university_name,
        "active_session_mode": _effective_session_mode(user),
        "access_token": issue_access_token(user.id),
        "token_type": "bearer",
    }


class SessionModeUpdate(BaseModel):
    mode: str


def _effective_session_mode(user: models.User) -> str:
    """Return the server-authoritative content/storage mode for this account."""
    role = (user.role or "").strip().lower()
    if role in {"simple user", "simple_user", "simpleuser", "user"}:
        return "simple"
    return "simple" if (user.active_session_mode or "").strip().lower() == "simple" else "academic"


def _require_active_mode_allows_group(group: models.Group | None, user: models.User) -> models.Group:
    if not group:
        raise HTTPException(status_code=404, detail="Group not found")
    # Official university groups are academic data.  A Student/Teacher in
    # Simple mode may still use ordinary social groups, but cannot read or
    # send in a campus class until switching back through /session-mode.
    if group.university_id is not None and _effective_session_mode(user) == "simple":
        raise HTTPException(status_code=403, detail="Switch back to academic mode to access campus groups.")
    return group


def _require_group_member(db: Session, group: models.Group, user: models.User) -> None:
    """Enforce membership for every group read/write entry point."""
    if group.teacher_id == user.id:
        return
    membership = db.query(models.GroupStudent.id).filter(
        models.GroupStudent.group_id == group.id,
        models.GroupStudent.student_id == user.id,
        (models.GroupStudent.is_left == False) | (models.GroupStudent.is_left == None),
    ).first()
    if not membership:
        raise HTTPException(status_code=403, detail="Only active group members can access this chat.")


@app.post("/session-mode")
def update_session_mode(
    request: SessionModeUpdate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    requested = request.mode.strip().lower()
    if requested not in {"academic", "simple"}:
        raise HTTPException(status_code=422, detail="Mode must be 'academic' or 'simple'.")

    role = (current_user.role or "").strip().lower()
    is_native_simple = role in {"simple user", "simple_user", "simpleuser", "user"}
    if is_native_simple and requested != "simple":
        raise HTTPException(status_code=403, detail="A Simple User account cannot enter academic mode.")

    current_user.active_session_mode = requested
    db.commit()
    return {"message": "Session mode updated", "active_session_mode": requested}

# ─── Forgot Password Routes ────────────────────────────────────────────────────

class ForgotPasswordResetRequest(BaseModel):
    email: EmailStr
    otp_code: str
    new_password: str

@app.post("/forgot-password/request-otp")
def forgot_password_request_otp(req: OTPRequest, background_tasks: BackgroundTasks, db: Session = Depends(get_db)):
    """Send OTP to user's email for password reset. Email must already exist."""
    user = db.query(models.User).filter(models.User.email == req.email).first()
    if not user:
        raise HTTPException(status_code=404, detail="No account found with this email.")

    otp_code = str(random.randint(100000, 999999))

    db_otp = db.query(models.OTP).filter(models.OTP.email == req.email).first()
    if db_otp:
        db_otp.otp_code = otp_code
    else:
        db_otp = models.OTP(email=req.email, otp_code=otp_code)
        db.add(db_otp)

    db.commit()
    background_tasks.add_task(send_otp_email, req.email, otp_code)
    return {"message": "OTP sent to your email for password reset."}

@app.post("/forgot-password/reset")
def forgot_password_reset(req: ForgotPasswordResetRequest, db: Session = Depends(get_db)):
    """Verify OTP and reset the user's password."""
    db_otp = db.query(models.OTP).filter(models.OTP.email == req.email).first()
    if not db_otp or db_otp.otp_code != req.otp_code:
        raise HTTPException(status_code=400, detail="Invalid or expired OTP.")

    user = db.query(models.User).filter(models.User.email == req.email).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found.")

    user.password = password_context.hash(req.new_password)
    db.delete(db_otp)
    db.commit()
    return {"message": "Password reset successfully. You can now log in."}

@app.get("/users/{user_id}")
def get_user(user_id: int, db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user

@app.get("/users/by-username/{username}")
def get_user_by_username(username: str, db: Session = Depends(get_db)):
    """
    Robust case-insensitive exact match lookup for Teacher, Student, or Simple User.
    Trims whitespace and optional '@' prefix.
    """
    from sqlalchemy import func
    clean_username = username.strip().removeprefix("@").lower()
    user = db.query(models.User).filter(func.lower(models.User.username) == clean_username).first()
    if not user:
        raise HTTPException(status_code=404, detail=f"User '@{username}' not found")
    return user
class UserProfileUpdate(BaseModel):
    name: Optional[str] = None
    profile_pic: Optional[str] = None

@app.patch("/users/{user_id}/profile")
def update_user_profile(user_id: int, data: UserProfileUpdate, db: Session = Depends(get_db)):
    """Update user's display name and/or profile picture."""
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    if data.name and data.name.strip():
        user.name = data.name.strip()
    if data.profile_pic is not None:
        user.profile_pic = data.profile_pic
    db.commit()
    db.refresh(user)
    return user

# ── FCM Token Registration ─────────────────────────────────────────────────────

class FcmTokenUpdate(BaseModel):
    token: str
    platform: Optional[str] = "android"

@app.post("/users/{user_id}/fcm-token")
def update_fcm_token(user_id: int, data: FcmTokenUpdate, db: Session = Depends(get_db)):
    """
    Register or refresh the FCM push notification token for a user.

    Called by the Android app:
      - Right after login (if an FCM token was already saved locally)
      - Whenever Firebase rotates the token (onNewToken callback)

    Uses UPSERT: one row per user_id — updates existing row if present,
    inserts a new one if not. This ensures the backend always has the
    freshest token for targeted push delivery.
    """
    # Verify user exists
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    if not data.token or not data.token.strip():
        raise HTTPException(status_code=400, detail="FCM token must not be blank")

    # Upsert by token (one row per physical device):
    # If this device token already exists, bind it to this user_id.
    # Otherwise insert a new token record for this user and device.
    token_val = data.token.strip()
    existing = db.query(models.UserFcmToken).filter(
        models.UserFcmToken.token == token_val
    ).first()

    if existing:
        existing.user_id = user_id
        existing.platform = data.platform or "android"
        existing.updated_at = datetime.datetime.utcnow()
    else:
        new_record = models.UserFcmToken(
            user_id=user_id,
            token=token_val,
            platform=data.platform or "android",
            updated_at=datetime.datetime.utcnow()
        )
        db.add(new_record)

    db.commit()
    return {
        "message": "FCM token updated",
        "user_id": user_id,
        "platform": data.platform or "android"
    }

@app.delete("/users/{user_id}/fcm-token")
def remove_fcm_token(user_id: int, token: str, db: Session = Depends(get_db)):
    """Detach one device token when its account signs out."""
    cleaned_token = token.strip()
    if not cleaned_token:
        raise HTTPException(status_code=400, detail="FCM token must not be blank")
    deleted = db.query(models.UserFcmToken).filter(
        models.UserFcmToken.user_id == user_id,
        models.UserFcmToken.token == cleaned_token,
    ).delete(synchronize_session=False)
    db.commit()
    return {"message": "FCM token removed", "removed": bool(deleted)}
class GroupProfileUpdate(BaseModel):
    profile_pic: Optional[str] = None

@app.patch("/groups/{group_id}/profile")
def update_group_profile(group_id: int, data: GroupProfileUpdate, db: Session = Depends(get_db)):
    """Update group's profile picture (base64 string)."""
    group = db.query(models.Group).filter(models.Group.id == group_id).first()
    if not group:
        raise HTTPException(status_code=404, detail="Group not found")
    if data.profile_pic is not None:
        group.profile_pic = data.profile_pic
    db.commit()
    db.refresh(group)
    return {"message": "Group profile updated", "profile_pic": group.profile_pic}




class CustomGroupCreateRequest(BaseModel):
    name: str
    creator_id: int
    member_ids: List[int]
    profile_pic: Optional[str] = None

@app.post("/groups/create")
def create_custom_group(data: CustomGroupCreateRequest, db: Session = Depends(get_db)):
    """
    Creates a new custom group for Simple Users, Teachers, or Students.
    - Creator is assigned as the group Admin (stored in teacher_id).
    - Creator and all selected member_ids are enrolled into group_students.
    - No duplicate members allowed.
    """
    if not data.name or not data.name.strip():
        raise HTTPException(status_code=400, detail="Group name cannot be empty")

    creator = db.query(models.User).filter(models.User.id == data.creator_id).first()
    if not creator:
        raise HTTPException(status_code=404, detail="Creator not found")

    new_group = models.Group(
        name=data.name.strip(),
        degree=None,
        major=None,
        academic_year=None,
        subject_id=None,
        teacher_id=data.creator_id,
        profile_pic=data.profile_pic
    )
    db.add(new_group)
    db.commit()
    db.refresh(new_group)

    # Add creator as group member (Admin member)
    db.add(models.GroupStudent(group_id=new_group.id, student_id=data.creator_id, is_left=False))

    # Add all selected members without duplicates
    added_ids = {data.creator_id}
    for m_id in data.member_ids:
        if m_id not in added_ids:
            user = db.query(models.User).filter(models.User.id == m_id).first()
            if user:
                db.add(models.GroupStudent(group_id=new_group.id, student_id=m_id, is_left=False))
                added_ids.add(m_id)

    db.commit()
    return {"message": "Group created successfully", "group": new_group}


# ─── Group Routes ──────────────────────────────────────────────────────────────
@app.get("/users/{user_id}/groups")
def get_user_groups(
    user_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    if user_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only request your own groups.")

    # 1. Groups where user is creator/admin (teacher_id)
    created_groups = db.query(models.Group).filter(models.Group.teacher_id == user_id).all()

    # 2. Groups where user is an active member in GroupStudent
    student_links = db.query(models.GroupStudent).filter(
        models.GroupStudent.student_id == user_id,
        (models.GroupStudent.is_left == False) | (models.GroupStudent.is_left == None)
    ).all()
    member_group_ids = {link.group_id for link in student_links}
    member_groups = db.query(models.Group).filter(models.Group.id.in_(member_group_ids)).all() if member_group_ids else []

    seen = set()
    merged = []
    for g in (created_groups + member_groups):
        if g.university_id is not None and _effective_session_mode(current_user) == "simple":
            continue
        if g.id not in seen:
            seen.add(g.id)
            merged.append(g)
    return merged


# ─── Chat ──────────────────────────────────────────────────────────────────────

@app.post("/groups/{group_id}/chat")
def send_message(
    group_id: int, 
    msg: ChatMessageCreate, 
    background_tasks: BackgroundTasks, 
    idempotency_key: Optional[str] = Header(None, alias="Idempotency-Key"),
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    if msg.sender_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only send as your own account.")
    group = _require_active_mode_allows_group(
        db.query(models.Group).filter(models.Group.id == group_id).first(), current_user
    )
    _require_group_member(db, group, current_user)
    clean_key = (idempotency_key or msg.idempotency_key or "").strip()
    if clean_key:
        existing = db.query(models.ChatMessage).filter(models.ChatMessage.idempotency_key == clean_key).first()
        if existing:
            return {
                "id": existing.id,
                "group_id": existing.group_id,
                "sender_id": existing.sender_id,
                "sender_name": existing.sender_name,
                "text": existing.text,
                "message_type": existing.message_type or "text",
                "media_url": existing.media_url,
                "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                "is_read": bool(existing.is_read),
                "is_delivered": bool(existing.is_delivered),
                "is_deleted": bool(existing.is_deleted),
                "deleted_by": existing.deleted_by,
                "is_pinned": bool(existing.is_pinned),
                "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                "idempotency_key": existing.idempotency_key
            }

    sender = db.query(models.User).filter(models.User.id == msg.sender_id).first()
    
    # Check if any OTHER group member is online
    student_links = db.query(models.GroupStudent).filter(models.GroupStudent.group_id == group_id).all()
    group = db.query(models.Group).filter(models.Group.id == group_id).first()
    other_mids = {link.student_id for link in student_links if link.student_id != msg.sender_id}
    if group and group.teacher_id and group.teacher_id != msg.sender_id:
        other_mids.add(group.teacher_id)
        
    is_delivered = False
    if other_mids:
        import datetime
        now = datetime.datetime.utcnow()
        online_statuses = db.query(models.UserOnlineStatus).filter(models.UserOnlineStatus.user_id.in_(other_mids)).all()
        for st in online_statuses:
            if st.last_seen:
                last = st.last_seen
                if last.tzinfo is not None:
                    now = now.replace(tzinfo=datetime.timezone.utc)
                if (now - last).total_seconds() <= 25:
                    is_delivered = True
                    break

    chat = models.ChatMessage(
        group_id=group_id,
        sender_id=msg.sender_id,
        sender_name=sender.name if sender else "Unknown",
        text=msg.text,
        message_type=msg.message_type or "text",
        media_url=msg.media_url,
        is_delivered=is_delivered,
        idempotency_key=clean_key if clean_key else None
    )
    db.add(chat)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        if clean_key:
            existing = db.query(models.ChatMessage).filter(models.ChatMessage.idempotency_key == clean_key).first()
            if existing:
                return {
                    "id": existing.id, "group_id": existing.group_id, "sender_id": existing.sender_id,
                    "sender_name": existing.sender_name, "text": existing.text,
                    "message_type": existing.message_type or "text", "media_url": existing.media_url,
                    "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                    "is_read": bool(existing.is_read), "is_delivered": bool(existing.is_delivered),
                    "is_deleted": bool(existing.is_deleted), "deleted_by": existing.deleted_by,
                    "is_pinned": bool(existing.is_pinned),
                    "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                    "idempotency_key": existing.idempotency_key, "duration_sec": existing.duration_sec or 0
                }
        raise
    db.refresh(chat)

    # Dispatch FCM Push Notification to all group members in background
    background_tasks.add_task(
        send_group_fcm_push,
        sender_id=chat.sender_id,
        group_id=chat.group_id,
        message_id=chat.id,
        text=chat.text,
        message_type=chat.message_type or "text",
        media_url=chat.media_url or ""
    )
    return {
        "id": chat.id,
        "group_id": chat.group_id,
        "sender_id": chat.sender_id,
        "sender_name": chat.sender_name,
        "text": chat.text,
        "message_type": chat.message_type or "text",
        "media_url": chat.media_url,
        "timestamp": chat.timestamp.isoformat() if hasattr(chat.timestamp, 'isoformat') else str(chat.timestamp),
        "is_read": bool(chat.is_read),
        "is_delivered": bool(chat.is_delivered),
        "is_deleted": bool(chat.is_deleted),
        "deleted_by": chat.deleted_by,
        "is_pinned": bool(chat.is_pinned),
        "pinned_at": chat.pinned_at.isoformat() if (chat.pinned_at and hasattr(chat.pinned_at, 'isoformat')) else None,
        "idempotency_key": chat.idempotency_key
    }

@app.post("/groups/{group_id}/chat/upload")
def upload_group_media(
    group_id: int,
    sender_id: int = Form(...),
    text: str = Form(""),
    message_type: str = Form("file"),
    duration_sec: int = Form(0),
    file: UploadFile = File(...),
    idempotency_key: Optional[str] = Header(None, alias="Idempotency-Key"),
    form_idempotency_key: Optional[str] = Form(None, alias="idempotency_key"),
    background_tasks: BackgroundTasks = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    if sender_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only upload as your own account.")
    group = _require_active_mode_allows_group(
        db.query(models.Group).filter(models.Group.id == group_id).first(), current_user
    )
    _require_group_member(db, group, current_user)
    clean_key = (idempotency_key or form_idempotency_key or "").strip()
    if clean_key:
        existing = db.query(models.ChatMessage).filter(models.ChatMessage.idempotency_key == clean_key).first()
        if existing:
            return {
                "id": existing.id,
                "group_id": existing.group_id,
                "sender_id": existing.sender_id,
                "sender_name": existing.sender_name,
                "text": existing.text,
                "message_type": existing.message_type or "file",
                "media_url": existing.media_url,
                "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                "is_read": bool(existing.is_read),
                "is_delivered": bool(existing.is_delivered),
                "is_deleted": bool(existing.is_deleted),
                "deleted_by": existing.deleted_by,
                "is_pinned": bool(existing.is_pinned),
                "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                "idempotency_key": existing.idempotency_key,
                "duration_sec": existing.duration_sec or 0
            }

    _enforce_video_upload_limit(file, message_type)
    media_asset = save_and_upload_file_asset(
        file, upload_dir=UPLOAD_DIR, folder="educonnect/group_messages", media_type=message_type
    )
    media_url = media_asset.url
    sender = db.query(models.User).filter(models.User.id == sender_id).first()

    # Check if any OTHER group member is online
    student_links = db.query(models.GroupStudent).filter(models.GroupStudent.group_id == group_id).all()
    group = db.query(models.Group).filter(models.Group.id == group_id).first()
    other_mids = {link.student_id for link in student_links if link.student_id != sender_id}
    if group and group.teacher_id and group.teacher_id != sender_id:
        other_mids.add(group.teacher_id)
        
    is_delivered = False
    if other_mids:
        import datetime
        now = datetime.datetime.utcnow()
        online_statuses = db.query(models.UserOnlineStatus).filter(models.UserOnlineStatus.user_id.in_(other_mids)).all()
        for st in online_statuses:
            if st.last_seen:
                last = st.last_seen
                if last.tzinfo is not None:
                    now = now.replace(tzinfo=datetime.timezone.utc)
                if (now - last).total_seconds() <= 25:
                    is_delivered = True
                    break

    chat = models.ChatMessage(
        group_id=group_id,
        sender_id=sender_id,
        sender_name=sender.name if sender else "Unknown",
        text=text,
        message_type=message_type,
        media_url=media_url,
        media_public_id=media_asset.public_id,
        media_resource_type=media_asset.resource_type,
        is_delivered=is_delivered,
        idempotency_key=clean_key if clean_key else None,
        duration_sec=duration_sec
    )
    db.add(chat)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        if clean_key:
            existing = db.query(models.ChatMessage).filter(models.ChatMessage.idempotency_key == clean_key).first()
            if existing:
                return {
                    "id": existing.id, "group_id": existing.group_id, "sender_id": existing.sender_id,
                    "sender_name": existing.sender_name, "text": existing.text,
                    "message_type": existing.message_type or "file", "media_url": existing.media_url,
                    "timestamp": existing.timestamp.isoformat() if hasattr(existing.timestamp, 'isoformat') else str(existing.timestamp),
                    "is_read": bool(existing.is_read), "is_delivered": bool(existing.is_delivered),
                    "is_deleted": bool(existing.is_deleted), "deleted_by": existing.deleted_by,
                    "is_pinned": bool(existing.is_pinned),
                    "pinned_at": existing.pinned_at.isoformat() if (existing.pinned_at and hasattr(existing.pinned_at, 'isoformat')) else None,
                    "idempotency_key": existing.idempotency_key, "duration_sec": existing.duration_sec or 0
                }
        raise
    db.refresh(chat)

    # Dispatch FCM Push Notification to all group members in background
    if background_tasks:
        background_tasks.add_task(
            send_group_fcm_push,
            sender_id=chat.sender_id,
            group_id=chat.group_id,
            message_id=chat.id,
            text=chat.text or (f"Sent a {message_type}"),
            message_type=chat.message_type or "file",
            duration_sec=duration_sec,
            media_url=chat.media_url or ""
        )
    return {
        "id": chat.id,
        "group_id": chat.group_id,
        "sender_id": chat.sender_id,
        "sender_name": chat.sender_name,
        "text": chat.text,
        "message_type": chat.message_type or "file",
        "media_url": chat.media_url,
        "timestamp": chat.timestamp.isoformat() if hasattr(chat.timestamp, 'isoformat') else str(chat.timestamp),
        "is_read": bool(chat.is_read),
        "is_delivered": bool(chat.is_delivered),
        "is_deleted": bool(chat.is_deleted),
        "deleted_by": chat.deleted_by,
        "is_pinned": bool(chat.is_pinned),
        "pinned_at": chat.pinned_at.isoformat() if (chat.pinned_at and hasattr(chat.pinned_at, 'isoformat')) else None,
        "idempotency_key": chat.idempotency_key,
        "duration_sec": chat.duration_sec or 0
    }


def _resumable_personal_message_dict(message):
    return {
        "id": message.id, "sender_id": message.sender_id, "receiver_id": message.receiver_id,
        "text": message.text, "message_type": message.message_type or "file", "media_url": message.media_url,
        "timestamp": message.timestamp.isoformat() if hasattr(message.timestamp, 'isoformat') else str(message.timestamp),
        "is_read": bool(message.is_read), "is_delivered": bool(message.is_delivered),
        "is_deleted": bool(message.is_deleted), "deleted_by": message.deleted_by,
        "is_pinned": bool(message.is_pinned),
        "pinned_at": message.pinned_at.isoformat() if (message.pinned_at and hasattr(message.pinned_at, 'isoformat')) else None,
        "idempotency_key": message.idempotency_key, "duration_sec": message.duration_sec or 0
    }


def _resumable_group_message_dict(message):
    return {
        "id": message.id, "group_id": message.group_id, "sender_id": message.sender_id,
        "sender_name": message.sender_name, "text": message.text,
        "message_type": message.message_type or "file", "media_url": message.media_url,
        "timestamp": message.timestamp.isoformat() if hasattr(message.timestamp, 'isoformat') else str(message.timestamp),
        "is_read": bool(message.is_read), "is_delivered": bool(message.is_delivered),
        "is_deleted": bool(message.is_deleted), "deleted_by": message.deleted_by,
        "is_pinned": bool(message.is_pinned),
        "pinned_at": message.pinned_at.isoformat() if (message.pinned_at and hasattr(message.pinned_at, 'isoformat')) else None,
        "idempotency_key": message.idempotency_key, "duration_sec": message.duration_sec or 0
    }


def _resumable_group_delivered(db: Session, group_id: int, sender_id: int) -> bool:
    group = db.query(models.Group).filter(models.Group.id == group_id).first()
    if not group:
        raise HTTPException(status_code=404, detail="Group not found")
    recipient_ids = {
        link.student_id for link in db.query(models.GroupStudent).filter(
            models.GroupStudent.group_id == group_id
        ).all() if link.student_id != sender_id
    }
    if group.teacher_id and group.teacher_id != sender_id:
        recipient_ids.add(group.teacher_id)
    if not recipient_ids:
        return False
    now = datetime.datetime.utcnow()
    for online in db.query(models.UserOnlineStatus).filter(models.UserOnlineStatus.user_id.in_(recipient_ids)).all():
        if online.last_seen:
            check_now = now.replace(tzinfo=datetime.timezone.utc) if online.last_seen.tzinfo else now
            if (check_now - online.last_seen).total_seconds() <= 25:
                return True
    return False


@app.post("/transfers/resumable-upload/chunk")
def upload_resumable_media_chunk(
    transfer_id: str = Form(...),
    chat_type: str = Form(...),
    sender_id: int = Form(...),
    peer_id: int = Form(...),
    text: str = Form(""),
    message_type: str = Form("file"),
    duration_sec: int = Form(0),
    file_name: str = Form("file"),
    total_bytes: int = Form(...),
    offset: int = Form(...),
    is_final: bool = Form(False),
    chunk: UploadFile = File(...),
    background_tasks: BackgroundTasks = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """Append an acknowledged media chunk and create the chat message only after completion."""
    clean_transfer_id = "".join(c for c in transfer_id if c.isalnum() or c in "-_")[:128]
    clean_chat_type = (chat_type or "").strip().lower()
    if not clean_transfer_id or clean_chat_type not in ("personal", "group"):
        raise HTTPException(status_code=400, detail="Invalid resumable upload")
    if total_bytes <= 0 or offset < 0 or offset > total_bytes:
        raise HTTPException(status_code=400, detail="Invalid upload offset")
    if (message_type or "").strip().lower() == "video" and total_bytes > MAX_VIDEO_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail=VIDEO_UPLOAD_TOO_LARGE_MESSAGE)

    if sender_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only upload as your own account.")

    sender = db.query(models.User).filter(models.User.id == sender_id).first()
    if not sender:
        raise HTTPException(status_code=404, detail="Sender not found")
    if clean_chat_type == "personal":
        _require_personal_message_allowed(db, sender_id, peer_id)
        existing_message = db.query(models.PersonalMessage).filter(
            models.PersonalMessage.idempotency_key == clean_transfer_id
        ).first()
        if existing_message:
            return {"received_bytes": total_bytes, "total_bytes": total_bytes, "completed": True,
                    "personal_message": _resumable_personal_message_dict(existing_message), "group_message": None}
    else:
        group = _require_active_mode_allows_group(
            db.query(models.Group).filter(models.Group.id == peer_id).first(), current_user
        )
        _require_group_member(db, group, current_user)
        existing_message = db.query(models.ChatMessage).filter(
            models.ChatMessage.idempotency_key == clean_transfer_id
        ).first()
        if existing_message:
            return {"received_bytes": total_bytes, "total_bytes": total_bytes, "completed": True,
                    "personal_message": None, "group_message": _resumable_group_message_dict(existing_message)}

    upload = db.query(models.ResumableUpload).filter(
        models.ResumableUpload.transfer_id == clean_transfer_id
    ).first()
    if not upload:
        temp_path = os.path.join(RESUMABLE_UPLOAD_DIR, f"{clean_transfer_id}.part")
        upload = models.ResumableUpload(
            transfer_id=clean_transfer_id, chat_type=clean_chat_type, sender_id=sender_id, peer_id=peer_id,
            text=text, message_type=message_type or "file", duration_sec=max(0, duration_sec),
            file_name=os.path.basename(file_name or "file"), total_bytes=total_bytes,
            received_bytes=0, temp_path=temp_path
        )
        db.add(upload)
        db.commit()
    elif (upload.chat_type != clean_chat_type or upload.sender_id != sender_id or upload.peer_id != peer_id or
          upload.total_bytes != total_bytes):
        raise HTTPException(status_code=409, detail="Upload metadata does not match the saved transfer")

    actual_received = os.path.getsize(upload.temp_path) if os.path.exists(upload.temp_path) else 0
    if actual_received != upload.received_bytes:
        upload.received_bytes = actual_received
        upload.updated_at = datetime.datetime.utcnow()
        db.commit()

    # A retry may repeat an already acknowledged chunk after the client lost its response.
    # The app may have saved an acknowledgement that was lost on the server
    # (for example after a server restart). Return the authoritative offset so
    # the client can seek back and safely resend from this exact byte.
    if offset > actual_received:
        return {"received_bytes": actual_received, "total_bytes": total_bytes, "completed": False,
                "personal_message": None, "group_message": None}
    if offset == actual_received and actual_received < total_bytes:
        os.makedirs(os.path.dirname(upload.temp_path), exist_ok=True)
        with open(upload.temp_path, "ab") as out:
            while True:
                data = chunk.file.read(1024 * 1024)
                if not data:
                    break
                if actual_received + len(data) > total_bytes:
                    raise HTTPException(status_code=400, detail="Upload exceeds declared size")
                out.write(data)
                actual_received += len(data)
        upload.received_bytes = actual_received
        upload.updated_at = datetime.datetime.utcnow()
        db.commit()

    if actual_received < total_bytes:
        return {"received_bytes": actual_received, "total_bytes": total_bytes, "completed": False,
                "personal_message": None, "group_message": None}
    if actual_received != total_bytes:
        raise HTTPException(status_code=400, detail="Upload size mismatch")
    if not is_final and offset < total_bytes:
        return {"received_bytes": actual_received, "total_bytes": total_bytes, "completed": False,
                "personal_message": None, "group_message": None}

    try:
        folder = "educonnect/personal_messages" if clean_chat_type == "personal" else "educonnect/group_messages"
        media_asset = save_and_upload_path_asset(
            upload.temp_path, upload.file_name, upload_dir=UPLOAD_DIR, folder=folder,
            media_type=upload.message_type
        )
        media_url = media_asset.url
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Unable to finish upload. Please resume again.") from exc

    if clean_chat_type == "personal":
        # Recheck immediately before message creation in case either user blocked during the upload.
        _require_personal_message_allowed(db, sender_id, peer_id)
        receiver_status = db.query(models.UserOnlineStatus).filter(models.UserOnlineStatus.user_id == peer_id).first()
        delivered = False
        if receiver_status and receiver_status.last_seen:
            now = datetime.datetime.utcnow()
            check_now = now.replace(tzinfo=datetime.timezone.utc) if receiver_status.last_seen.tzinfo else now
            delivered = (check_now - receiver_status.last_seen).total_seconds() <= 25
        message = models.PersonalMessage(
            sender_id=sender_id, receiver_id=peer_id, text=upload.text, message_type=upload.message_type,
            media_url=media_url, media_public_id=media_asset.public_id,
            media_resource_type=media_asset.resource_type, is_delivered=delivered, idempotency_key=clean_transfer_id,
            duration_sec=upload.duration_sec
        )
    else:
        message = models.ChatMessage(
            group_id=peer_id, sender_id=sender_id, sender_name=sender.name or "Unknown", text=upload.text,
            message_type=upload.message_type, media_url=media_url,
            media_public_id=media_asset.public_id, media_resource_type=media_asset.resource_type,
            is_delivered=_resumable_group_delivered(db, peer_id, sender_id),
            idempotency_key=clean_transfer_id, duration_sec=upload.duration_sec
        )
    db.add(message)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        # Stable idempotency key guarantees a retry never creates a duplicate chat message.
        existing = (db.query(models.PersonalMessage).filter(models.PersonalMessage.idempotency_key == clean_transfer_id).first()
                    if clean_chat_type == "personal" else
                    db.query(models.ChatMessage).filter(models.ChatMessage.idempotency_key == clean_transfer_id).first())
        if not existing:
            raise
        message = existing
    db.refresh(message)

    if background_tasks:
        if clean_chat_type == "personal":
            background_tasks.add_task(send_personal_fcm_push, sender_id=sender_id, receiver_id=peer_id,
                                      message_id=message.id, text=message.text or f"Sent a {message.message_type}",
                                      message_type=message.message_type or "file", media_url=message.media_url or "")
        else:
            background_tasks.add_task(send_group_fcm_push, sender_id=sender_id, group_id=peer_id,
                                      message_id=message.id, text=message.text or f"Sent a {message.message_type}",
                                      message_type=message.message_type or "file", media_url=message.media_url or "")
    try:
        if os.path.exists(upload.temp_path):
            os.remove(upload.temp_path)
        db.delete(upload)
        db.commit()
    except Exception:
        db.rollback()

    return {
        "received_bytes": total_bytes, "total_bytes": total_bytes, "completed": True,
        "personal_message": _resumable_personal_message_dict(message) if clean_chat_type == "personal" else None,
        "group_message": _resumable_group_message_dict(message) if clean_chat_type == "group" else None
    }


@app.post("/posts/media/resumable-upload/chunk")
def upload_resumable_post_media_chunk(
    transfer_id: str = Form(...),
    author_id: int = Form(...),
    media_type: str = Form(...),
    file_name: str = Form("file"),
    total_bytes: int = Form(...),
    offset: int = Form(...),
    is_final: bool = Form(False),
    chunk: UploadFile = File(...),
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Persist a post attachment in acknowledged chunks before the post is published."""
    clean_id = "post_" + "".join(c for c in transfer_id if c.isalnum() or c in "-_")[:120]
    clean_type = (media_type or "").strip().upper()
    if not clean_id or clean_type not in {"IMAGE", "VIDEO", "AUDIO", "VOICE", "DOCUMENT"}:
        raise HTTPException(status_code=400, detail="Invalid post media upload")
    if total_bytes <= 0 or offset < 0 or offset > total_bytes:
        raise HTTPException(status_code=400, detail="Invalid upload offset")
    if clean_type == "VIDEO" and total_bytes > MAX_VIDEO_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail=VIDEO_UPLOAD_TOO_LARGE_MESSAGE)
    if author_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only upload media for your own post.")

    upload = db.query(models.ResumableUpload).filter(
        models.ResumableUpload.transfer_id == clean_id
    ).first()
    if upload and (upload.chat_type != "post" or upload.sender_id != author_id or
                   upload.total_bytes != total_bytes or upload.message_type != clean_type):
        raise HTTPException(status_code=409, detail="Upload metadata does not match the saved transfer")
    if upload and upload.media_url:
        return {"received_bytes": upload.total_bytes, "total_bytes": upload.total_bytes,
                "completed": True, "media_url": upload.media_url}
    if not upload:
        temp_path = os.path.join(RESUMABLE_UPLOAD_DIR, f"{clean_id}.part")
        upload = models.ResumableUpload(
            transfer_id=clean_id, chat_type="post", sender_id=author_id, peer_id=0,
            text="", message_type=clean_type, file_name=os.path.basename(file_name or "file"),
            total_bytes=total_bytes, received_bytes=0, temp_path=temp_path
        )
        db.add(upload)
        db.commit()

    received = os.path.getsize(upload.temp_path) if os.path.exists(upload.temp_path) else 0
    if received != upload.received_bytes:
        upload.received_bytes = received
        upload.updated_at = datetime.datetime.utcnow()
        db.commit()
    if offset > received:
        return {"received_bytes": received, "total_bytes": total_bytes, "completed": False, "media_url": None}
    if offset == received and received < total_bytes:
        with open(upload.temp_path, "ab") as out:
            while True:
                data = chunk.file.read(1024 * 1024)
                if not data:
                    break
                if received + len(data) > total_bytes:
                    raise HTTPException(status_code=400, detail="Upload exceeds declared size")
                out.write(data)
                received += len(data)
        upload.received_bytes = received
        upload.updated_at = datetime.datetime.utcnow()
        db.commit()
    if received < total_bytes:
        return {"received_bytes": received, "total_bytes": total_bytes, "completed": False, "media_url": None}
    if received != total_bytes:
        raise HTTPException(status_code=400, detail="Upload size mismatch")
    if not is_final and offset < total_bytes:
        return {"received_bytes": received, "total_bytes": total_bytes, "completed": False, "media_url": None}

    try:
        media_url = save_and_upload_path(
            upload.temp_path, upload.file_name, upload_dir=UPLOAD_DIR, folder="educonnect/posts",
            media_type=clean_type
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Unable to finish post media upload. Please resume again.") from exc
    upload.media_url = media_url
    upload.received_bytes = total_bytes
    upload.updated_at = datetime.datetime.utcnow()
    db.commit()
    try:
        if os.path.exists(upload.temp_path):
            os.remove(upload.temp_path)
    except OSError:
        pass
    return {"received_bytes": total_bytes, "total_bytes": total_bytes, "completed": True, "media_url": media_url}

@app.get("/groups/{group_id}/chat")
def get_messages(
    group_id: int,
    before_id: Optional[int] = Query(None, ge=1),
    after_id: Optional[int] = Query(None, ge=0),
    limit: int = Query(50, ge=1, le=100),
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    # Check if any OTHER member of the group is online right now
    group = _require_active_mode_allows_group(
        db.query(models.Group).filter(models.Group.id == group_id).first(), current_user
    )
    _require_group_member(db, group, current_user)
    if group:
        student_links = db.query(models.GroupStudent).filter(models.GroupStudent.group_id == group_id).all()
        member_ids = {link.student_id for link in student_links}
        if group.teacher_id:
            member_ids.add(group.teacher_id)
        
        if member_ids:
            import datetime
            now = datetime.datetime.utcnow()
            online_statuses = db.query(models.UserOnlineStatus).filter(models.UserOnlineStatus.user_id.in_(member_ids)).all()
            delivered_count = 0
            for st in online_statuses:
                if st.last_seen:
                    last = st.last_seen
                    if last.tzinfo is not None:
                        now = now.replace(tzinfo=datetime.timezone.utc)
                    if (now - last).total_seconds() <= 25:
                        # This member is online -> mark messages sent by OTHER members in this group as delivered
                        delivered_count += db.query(models.ChatMessage).filter(
                            models.ChatMessage.group_id == group_id,
                            models.ChatMessage.sender_id != st.user_id,
                            models.ChatMessage.is_delivered == False
                        ).update({"is_delivered": True})
            if delivered_count:
                db.commit()

    message_query = db.query(models.ChatMessage).filter(
        models.ChatMessage.group_id == group_id
    )
    clear_state = db.query(models.GroupChatClearState).filter(
        models.GroupChatClearState.user_id == current_user.id,
        models.GroupChatClearState.group_id == group_id,
    ).first()
    if clear_state:
        message_query = message_query.filter(
            models.ChatMessage.id > clear_state.cleared_through_message_id
        )
    if after_id is not None:
        message_query = message_query.filter(models.ChatMessage.id > after_id)
        messages = message_query.order_by(models.ChatMessage.id.asc()).limit(limit).all()
    elif before_id is not None:
        message_query = message_query.filter(models.ChatMessage.id < before_id)
        messages = message_query.order_by(models.ChatMessage.id.desc()).limit(limit).all()
        messages.reverse()
    else:
        # Fetch newest-first efficiently, then restore chronological response order.
        messages = message_query.order_by(models.ChatMessage.id.desc()).limit(limit).all()
        messages.reverse()

    result = []
    for m in messages:
        ts_str = m.timestamp.isoformat() if hasattr(m.timestamp, 'isoformat') else str(m.timestamp)
        result.append({
            "id": m.id,
            "group_id": m.group_id,
            "sender_id": m.sender_id,
            "sender_name": m.sender_name,
            "text": m.text,
            "message_type": m.message_type or "text",
            "media_url": m.media_url,
            "is_media_expired": bool(m.is_media_expired),
            "timestamp": ts_str,
            "is_read": bool(m.is_read),
            "is_delivered": bool(m.is_delivered),
            "is_deleted": bool(m.is_deleted),
            "deleted_by": m.deleted_by,
            "is_pinned": bool(m.is_pinned),
            "pinned_at": m.pinned_at.isoformat() if (m.pinned_at and hasattr(m.pinned_at, 'isoformat')) else None,
            "idempotency_key": m.idempotency_key,
            "duration_sec": m.duration_sec or 0
        })
    return result


@app.post("/groups/{group_id}/chat/clear")
def clear_group_chat(
    group_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """Clear only this member's view; never delete shared group content."""
    group = _require_active_mode_allows_group(
        db.query(models.Group).filter(models.Group.id == group_id).first(), current_user
    )
    _require_group_member(db, group, current_user)
    latest = db.query(models.ChatMessage.id).filter(
        models.ChatMessage.group_id == group_id
    ).order_by(models.ChatMessage.id.desc()).first()
    cutoff = latest[0] if latest else 0
    state = db.query(models.GroupChatClearState).filter(
        models.GroupChatClearState.user_id == current_user.id,
        models.GroupChatClearState.group_id == group_id,
    ).first()
    if state:
        state.cleared_through_message_id = max(state.cleared_through_message_id, cutoff)
        state.updated_at = datetime.datetime.utcnow()
    else:
        db.add(models.GroupChatClearState(
            user_id=current_user.id,
            group_id=group_id,
            cleared_through_message_id=cutoff,
        ))
    db.commit()
    return {"message": "This group chat was cleared for you", "cleared_through_message_id": cutoff}

@app.post("/groups/{group_id}/chat/{message_id}/delete_everyone")
def delete_group_message_for_everyone(
    group_id: int,
    message_id: int,
    data: DeleteEveryoneRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    if data.user_id != current_user.id:
        raise HTTPException(status_code=403, detail="Request user does not match authenticated user")
    msg = db.query(models.ChatMessage).filter(models.ChatMessage.id == message_id, models.ChatMessage.group_id == group_id).first()
    if not msg:
        raise HTTPException(status_code=404, detail="Message not found")
    group = _require_active_mode_allows_group(
        db.query(models.Group).filter(models.Group.id == group_id).first(), current_user
    )
    _require_group_member(db, group, current_user)
    is_admin = group.teacher_id == current_user.id
    if msg.sender_id != data.user_id and not is_admin:
        raise HTTPException(status_code=403, detail="Not authorized to delete this message for everyone")

    msg.is_deleted = True
    msg.deleted_by = data.user_id
    msg.text = "🚫 This message was deleted"
    msg.media_url = None
    msg.is_pinned = False
    msg.pinned_at = None
    db.commit()
    db.refresh(msg)
    return {"message": "Message deleted for everyone", "id": msg.id, "is_deleted": True}

@app.post("/groups/{group_id}/chat/{message_id}/pin")
def pin_group_message(
    group_id: int,
    message_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    group = _require_active_mode_allows_group(
        db.query(models.Group).filter(models.Group.id == group_id).first(), current_user
    )
    _require_group_member(db, group, current_user)
    msg = db.query(models.ChatMessage).filter(models.ChatMessage.id == message_id, models.ChatMessage.group_id == group_id).first()
    if not msg:
        raise HTTPException(status_code=404, detail="Message not found")
    import datetime
    msg.is_pinned = True
    msg.pinned_at = datetime.datetime.utcnow()
    db.commit()
    db.refresh(msg)
    return {"message": "Message pinned", "id": msg.id, "is_pinned": True}

@app.post("/groups/{group_id}/chat/{message_id}/unpin")
def unpin_group_message(
    group_id: int,
    message_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    group = _require_active_mode_allows_group(
        db.query(models.Group).filter(models.Group.id == group_id).first(), current_user
    )
    _require_group_member(db, group, current_user)
    msg = db.query(models.ChatMessage).filter(models.ChatMessage.id == message_id, models.ChatMessage.group_id == group_id).first()
    if not msg:
        raise HTTPException(status_code=404, detail="Message not found")
    msg.is_pinned = False
    msg.pinned_at = None
    db.commit()
    db.refresh(msg)
    return {"message": "Message unpinned", "id": msg.id, "is_pinned": False}

# ─── Assignments ───────────────────────────────────────────────────────────────

@app.post("/assignments/")
def upload_assignment(
    group_id: int = Form(...),
    title: str = Form(...),
    description: str = Form(...),
    file: UploadFile = File(...),
    db: Session = Depends(get_db)
):
    _enforce_video_upload_limit(file)
    file_url = save_and_upload_file(file, upload_dir=UPLOAD_DIR, folder="educonnect/assignments")

    assignment = models.Assignment(
        group_id=group_id,
        title=title,
        description=description,
        file_url=file_url
    )
    db.add(assignment)
    db.commit()
    db.refresh(assignment)
    return assignment

@app.get("/groups/{group_id}/assignments")
def get_assignments(group_id: int, db: Session = Depends(get_db)):
    return db.query(models.Assignment).filter(models.Assignment.group_id == group_id).all()

# ─── Attendance ────────────────────────────────────────────────────────────────

@app.post("/attendance/")
def mark_attendance(data: AttendanceCreate, db: Session = Depends(get_db)):
    existing = db.query(models.Attendance).filter(
        models.Attendance.student_id == data.student_id,
        models.Attendance.group_id == data.group_id,
        models.Attendance.date == data.date
    ).first()

    if existing:
        existing.status = data.status
        db.commit()
        db.refresh(existing)
        return existing

    attendance = models.Attendance(
        student_id=data.student_id,
        group_id=data.group_id,
        date=data.date,
        status=data.status
    )
    db.add(attendance)
    db.commit()
    db.refresh(attendance)
    return attendance

@app.get("/groups/{group_id}/attendance")
def get_attendance(group_id: int, date: str, db: Session = Depends(get_db)):
    return db.query(models.Attendance).filter(
        models.Attendance.group_id == group_id,
        models.Attendance.date == date
    ).all()

@app.get("/groups/{group_id}/students")
def get_group_students(group_id: int, db: Session = Depends(get_db)):
    student_links = db.query(models.GroupStudent).filter(
        models.GroupStudent.group_id == group_id,
        (models.GroupStudent.is_left == False) | (models.GroupStudent.is_left == None)
    ).all()
    student_ids = [link.student_id for link in student_links]
    return db.query(models.User).filter(models.User.id.in_(student_ids)).all()

@app.get("/groups/{group_id}/members")
def get_group_members(group_id: int, db: Session = Depends(get_db)):
    """Fetch all active members of a group (Teacher + Students) for WhatsApp-like group info."""
    group = db.query(models.Group).filter(models.Group.id == group_id).first()
    if not group:
        raise HTTPException(status_code=404, detail="Group not found")

    # Build a stable, unique member order. A creator/teacher can also have an
    # older GroupStudent link, so extending two query results directly can show
    # that same person twice in Group Info.
    ordered_member_ids = []
    if group.teacher_id:
        ordered_member_ids.append(group.teacher_id)

    # Add active members after the creator/teacher.
    student_links = db.query(models.GroupStudent).filter(
        models.GroupStudent.group_id == group_id,
        (models.GroupStudent.is_left == False) | (models.GroupStudent.is_left == None)
    ).all()
    ordered_member_ids.extend(link.student_id for link in student_links)
    ordered_member_ids = list(dict.fromkeys(user_id for user_id in ordered_member_ids if user_id))
    if not ordered_member_ids:
        return []

    users_by_id = {
        user.id: user
        for user in db.query(models.User).filter(models.User.id.in_(ordered_member_ids)).all()
    }
    return [users_by_id[user_id] for user_id in ordered_member_ids if user_id in users_by_id]

class AddGroupMembersRequest(BaseModel):
    user_ids: List[int]

class RemoveGroupMemberRequest(BaseModel):
    admin_id: int
    user_id: int

@app.post("/groups/{group_id}/members/remove")
def remove_group_member(group_id: int, req: RemoveGroupMemberRequest, db: Session = Depends(get_db)):
    """Admin removes a member from the group (WhatsApp style)."""
    import datetime
    group = db.query(models.Group).filter(models.Group.id == group_id).first()
    if not group:
        raise HTTPException(status_code=404, detail="Group not found")

    # Verify requester is admin/creator
    if group.teacher_id != req.admin_id:
        raise HTTPException(status_code=403, detail="Only group admin can remove members")

    if req.user_id == req.admin_id:
        raise HTTPException(status_code=400, detail="Admin cannot remove themselves")

    user = db.query(models.User).filter(models.User.id == req.user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    link = db.query(models.GroupStudent).filter(
        models.GroupStudent.group_id == group_id,
        models.GroupStudent.student_id == req.user_id
    ).first()

    now = datetime.datetime.utcnow()
    if link:
        link.is_left = True
        link.left_at = now
    else:
        db.add(models.GroupStudent(
            group_id=group_id,
            student_id=req.user_id,
            is_left=True,
            left_at=now
        ))

    sys_msg = models.ChatMessage(
        group_id=group_id,
        sender_id=req.admin_id,
        sender_name="System",
        text=f"{user.name} was removed from the group",
        message_type="system"
    )
    db.add(sys_msg)
    db.commit()
    return {"message": f"{user.name} was removed from the group"}

@app.post("/groups/{group_id}/members")
def add_group_members(group_id: int, req: AddGroupMembersRequest, db: Session = Depends(get_db)):
    group = db.query(models.Group).filter(models.Group.id == group_id).first()
    if not group:
        raise HTTPException(status_code=404, detail="Group not found")
    
    added_names = []
    for uid in req.user_ids:
        user = db.query(models.User).filter(models.User.id == uid).first()
        if not user:
            continue
        link = db.query(models.GroupStudent).filter(
            models.GroupStudent.group_id == group_id,
            models.GroupStudent.student_id == uid
        ).first()
        if link:
            if link.is_left:
                link.is_left = False
                link.left_at = None
                added_names.append(user.name)
        else:
            db.add(models.GroupStudent(group_id=group_id, student_id=uid, is_left=False))
            added_names.append(user.name)

    if added_names:
        names_str = ", ".join(added_names)
        sys_msg = models.ChatMessage(
            group_id=group_id,
            sender_id=group.teacher_id or 1,
            sender_name="System",
            text=f"{names_str} joined the group",
            message_type="system"
        )
        db.add(sys_msg)

    db.commit()
    return {"message": "Members added successfully"}

@app.get("/groups/{group_id}")
def get_group_details(group_id: int, db: Session = Depends(get_db)):
    group = db.query(models.Group).filter(models.Group.id == group_id).first()
    if not group:
        raise HTTPException(status_code=404, detail="Group not found")
    return {
        "id": group.id,
        "name": group.name,
        "degree": group.degree,
        "major": group.major,
        "academic_year": group.academic_year,
        "teacher_id": group.teacher_id,
        "profile_pic": group.profile_pic
    }

@app.post("/groups/{group_id}/leave")
def leave_group(group_id: int, req: LeaveGroupRequest, db: Session = Depends(get_db)):
    """Leave group like WhatsApp: marks member as left, announces in chat, and keeps chat history."""
    import datetime
    user = db.query(models.User).filter(models.User.id == req.user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    group = db.query(models.Group).filter(models.Group.id == group_id).first()
    if not group:
        raise HTTPException(status_code=404, detail="Group not found")

    link = db.query(models.GroupStudent).filter(
        models.GroupStudent.group_id == group_id,
        models.GroupStudent.student_id == req.user_id
    ).first()

    now = datetime.datetime.utcnow()
    if link:
        link.is_left = True
        link.left_at = now
    else:
        db.add(models.GroupStudent(
            group_id=group_id,
            student_id=req.user_id,
            is_left=True,
            left_at=now
        ))

    # Add a system announcement message in group chat ("You left" / "[USER_NAME] left")
    sys_msg = models.ChatMessage(
        group_id=group_id,
        sender_id=req.user_id,
        sender_name="System",
        text=f"{user.name} left",
        message_type="system"
    )
    db.add(sys_msg)
    db.commit()

    return {"message": "Left group successfully", "is_left": True}

@app.get("/groups/{group_id}/membership/{user_id}")
def get_group_membership(group_id: int, user_id: int, db: Session = Depends(get_db)):
    """Check whether a user is an active participant or has left."""
    group = db.query(models.Group).filter(models.Group.id == group_id).first()
    if not group:
        return {"is_member": False, "is_left": False}

    if group.teacher_id == user_id:
        return {"is_member": True, "is_left": False}

    link = db.query(models.GroupStudent).filter(
        models.GroupStudent.group_id == group_id,
        models.GroupStudent.student_id == user_id
    ).first()

    if link and bool(link.is_left):
        return {
            "is_member": False,
            "is_left": True,
            "left_at": link.left_at.isoformat() if link.left_at else None
        }
    elif link:
        return {"is_member": True, "is_left": False}
    else:
        # Check by academic match
        user = db.query(models.User).filter(models.User.id == user_id).first()
        if user and user.degree == group.degree and user.major == group.major and user.academic_year == group.academic_year:
            return {"is_member": True, "is_left": False}
        return {"is_member": False, "is_left": False}


# ─── Mark as Read Endpoints ────────────────────────────────────────────────────

@app.post("/personal_messages/{user_id}/{other_user_id}/read")
def mark_personal_chat_read(user_id: int, other_user_id: int, db: Session = Depends(get_db)):
    """Mark all unread messages from other_user_id to user_id as read AND delivered."""
    db.query(models.PersonalMessage).filter(
        models.PersonalMessage.sender_id == other_user_id,
        models.PersonalMessage.receiver_id == user_id,
        models.PersonalMessage.is_read == False
    ).update({"is_read": True, "is_delivered": True})
    db.commit()
    return {"message": "Messages marked as read"}

@app.post("/groups/{group_id}/chat/read")
def mark_group_chat_read(group_id: int, user_id: int, db: Session = Depends(get_db)):
    """Update GroupReadStatus and mark group messages as read by others."""
    from sqlalchemy import text
    last_msg = db.query(models.ChatMessage).filter(
        models.ChatMessage.group_id == group_id
    ).order_by(models.ChatMessage.id.desc()).first()
    last_msg_id = last_msg.id if last_msg else 0
    existing = db.execute(
        text("SELECT id FROM group_read_statuses WHERE user_id=:u AND group_id=:g"),
        {"u": user_id, "g": group_id}
    ).fetchone()
    import datetime
    now = datetime.datetime.utcnow()
    if existing:
        db.execute(
            text("UPDATE group_read_statuses SET last_read_message_id=:m, last_read_at=:t WHERE user_id=:u AND group_id=:g"),
            {"m": last_msg_id, "t": now, "u": user_id, "g": group_id}
        )
    else:
        db.execute(
            text("INSERT INTO group_read_statuses (user_id, group_id, last_read_message_id, last_read_at) VALUES (:u, :g, :m, :t)"),
            {"u": user_id, "g": group_id, "m": last_msg_id, "t": now}
        )

    # Mark messages sent by OTHERS as is_read=True and is_delivered=True (so sender sees purple ticks)
    db.query(models.ChatMessage).filter(
        models.ChatMessage.group_id == group_id,
        models.ChatMessage.sender_id != user_id
    ).update({"is_read": True, "is_delivered": True})

    db.commit()
    return {"message": "Group chat marked as read"}


# ─── Unified Home Feed ─────────────────────────────────────────────────────────

@app.get("/home_feed/{user_id}")
def get_home_feed(user_id: int, db: Session = Depends(get_db)):
    """
    Returns a unified list of groups + direct chats sorted by most recent activity.
    Each item includes: type, id, name, last_message, last_sender, timestamp, unread_count, profile_pic.
    """
    # Active feed poll -> mark all incoming messages as delivered (double tick for sender)
    delivered_count = db.query(models.PersonalMessage).filter(
        models.PersonalMessage.receiver_id == user_id,
        models.PersonalMessage.is_delivered == False
    ).update({"is_delivered": True})
    if delivered_count:
        db.commit()

    result = []

    # ── 1. Groups ────────────────────────────────────────────────────────────────
    group_links = db.query(models.GroupStudent).filter(
        models.GroupStudent.student_id == user_id
    ).all()
    student_group_map = {link.group_id: bool(link.is_left) for link in group_links}

    teacher_groups = db.query(models.Group).filter(
        models.Group.teacher_id == user_id
    ).all()
    group_ids_as_teacher = {g.id for g in teacher_groups}

    all_group_ids = set(student_group_map.keys()) | group_ids_as_teacher
    all_groups = db.query(models.Group).filter(models.Group.id.in_(all_group_ids)).all() if all_group_ids else []

    for group in all_groups:
        is_user_left = student_group_map.get(group.id, False) if group.id not in group_ids_as_teacher else False

        last_msg = db.query(models.ChatMessage).filter(
            models.ChatMessage.group_id == group.id
        ).order_by(models.ChatMessage.timestamp.desc()).first()

        last_text = last_msg.text if last_msg else ""
        last_sender = "You" if (last_msg and last_msg.sender_id == user_id) else (last_msg.sender_name if last_msg else "")
        ts = last_msg.timestamp if last_msg else None
        ts_str = _fmt_timestamp(ts)

        ts_raw = ""
        if ts is not None:
            ts_raw = ts.isoformat() if hasattr(ts, 'isoformat') else str(ts)

        # Use GroupReadStatus to accurately count only truly unread messages
        from sqlalchemy import text as sqla_text
        read_status = db.execute(
            sqla_text("SELECT last_read_message_id FROM group_read_statuses WHERE user_id=:u AND group_id=:g"),
            {"u": user_id, "g": group.id}
        ).fetchone()
        last_read_id = read_status[0] if read_status else 0

        # Only count messages from other members that arrived after what user last read
        if is_user_left or (last_msg and last_msg.sender_id == user_id):
            # Left group or last message is mine → no unread
            unread_cnt = 0
        else:
            unread_cnt = db.query(models.ChatMessage).filter(
                models.ChatMessage.group_id == group.id,
                models.ChatMessage.sender_id != user_id,
                models.ChatMessage.id > last_read_id
            ).count()

        display_last_msg = "You left" if is_user_left and (not last_text or "left" in last_text.lower()) else last_text

        result.append({
            "type": "group",
            "id": group.id,
            "name": group.name,
            "degree": group.degree,
            "major": group.major,
            "academic_year": group.academic_year,
            "last_message": display_last_msg,
            "last_sender": "" if is_user_left and display_last_msg == "You left" else last_sender,
            "timestamp": ts_str,
            "timestamp_raw": ts_raw,
            "unread_count": min(unread_cnt, 99),
            "profile_pic": group.profile_pic,
            "is_left": is_user_left,
            "last_message_is_read": last_msg.is_read if (last_msg and hasattr(last_msg, 'is_read') and last_msg.is_read) else False,
            "last_message_is_delivered": last_msg.is_delivered if (last_msg and hasattr(last_msg, 'is_delivered') and last_msg.is_delivered) else False,
            "last_message_id": last_msg.id if last_msg else None,
            "last_sender_id": last_msg.sender_id if last_msg else None,
            "last_message_type": last_msg.message_type if (last_msg and hasattr(last_msg, 'message_type')) else "text",
            "last_message_duration_sec": last_msg.duration_sec if (last_msg and hasattr(last_msg, 'duration_sec') and last_msg.duration_sec) else 0,
            "role": "Student"
        })

    # ── 2. Personal / Direct Chats ────────────────────────────────────────────
    personal_msgs = db.query(models.PersonalMessage).filter(
        (models.PersonalMessage.sender_id == user_id) |
        (models.PersonalMessage.receiver_id == user_id)
    ).all()

    other_user_ids = set()
    for m in personal_msgs:
        if m.sender_id != user_id:
            other_user_ids.add(m.sender_id)
        if m.receiver_id != user_id:
            other_user_ids.add(m.receiver_id)

    for other_id in other_user_ids:
        other_user = db.query(models.User).filter(models.User.id == other_id).first()
        if not other_user:
            continue

        last_msg = db.query(models.PersonalMessage).filter(
            ((models.PersonalMessage.sender_id == user_id) &
             (models.PersonalMessage.receiver_id == other_id)) |
            ((models.PersonalMessage.sender_id == other_id) &
             (models.PersonalMessage.receiver_id == user_id))
        ).order_by(models.PersonalMessage.timestamp.desc()).first()

        last_text = last_msg.text if last_msg else ""
        is_mine = (last_msg.sender_id == user_id) if last_msg else False
        ts = last_msg.timestamp if last_msg else None
        ts_str = _fmt_timestamp(ts)

        ts_raw = ""
        if ts is not None:
            ts_raw = ts.isoformat() if hasattr(ts, 'isoformat') else str(ts)

        # Count only truly unread messages (is_read=False) from other user
        if is_mine:
            # Last message is mine → no unread
            direct_unread = 0
        else:
            direct_unread = db.query(models.PersonalMessage).filter(
                models.PersonalMessage.sender_id == other_id,
                models.PersonalMessage.receiver_id == user_id,
                models.PersonalMessage.is_read == False
            ).count()

        result.append({
            "type": "direct",
            "id": other_id,
            "name": other_user.name,
            "degree": None,
            "major": None,
            "academic_year": None,
            "last_message": last_text,
            "last_sender": "You" if is_mine else other_user.name,
            "timestamp": ts_str,
            "timestamp_raw": ts_raw,
            "unread_count": min(direct_unread, 99),
            "profile_pic": other_user.profile_pic,
            "last_message_is_read": last_msg.is_read if (last_msg and hasattr(last_msg, 'is_read') and last_msg.is_read) else False,
            "last_message_is_delivered": last_msg.is_delivered if (last_msg and hasattr(last_msg, 'is_delivered') and last_msg.is_delivered) else False,
            "last_message_id": last_msg.id if last_msg else None,
            "last_sender_id": last_msg.sender_id if last_msg else None,
            "last_message_type": last_msg.message_type if (last_msg and hasattr(last_msg, 'message_type')) else "text",
            "last_message_duration_sec": last_msg.duration_sec if (last_msg and hasattr(last_msg, 'duration_sec') and last_msg.duration_sec) else 0,
            "role": other_user.role or "Student"
        })

    # ── Sort by most recent ────────────────────────────────────────────────────
    result.sort(key=lambda x: x["timestamp_raw"], reverse=True)
    return result


# ─── Online Presence (Heartbeat) Endpoints ─────────────────────────────────────

@app.post("/users/{user_id}/online")
def ping_online(user_id: int, db: Session = Depends(get_db)):
    """
    Called every ~15s by the app to mark user as online.
    Also auto-marks personal messages as 'delivered' when receiver comes online.
    """
    import datetime
    now = datetime.datetime.utcnow()

    # Upsert last_seen
    existing = db.query(models.UserOnlineStatus).filter(
        models.UserOnlineStatus.user_id == user_id
    ).first()
    if existing:
        existing.last_seen = now
    else:
        db.add(models.UserOnlineStatus(user_id=user_id, last_seen=now))
    db.commit()

    # Auto-mark personal messages sent TO this user as delivered (not yet read)
    db.query(models.PersonalMessage).filter(
        models.PersonalMessage.receiver_id == user_id,
        models.PersonalMessage.is_delivered == False
    ).update({"is_delivered": True})

    # Auto-mark group messages sent to this user's groups by OTHER members as delivered
    user_student_gids = [g.group_id for g in db.query(models.GroupStudent).filter(models.GroupStudent.student_id == user_id).all()]
    user_teacher_gids = [g.id for g in db.query(models.Group).filter(models.Group.teacher_id == user_id).all()]
    all_u_gids = set(user_student_gids + user_teacher_gids)
    if all_u_gids:
        db.query(models.ChatMessage).filter(
            models.ChatMessage.group_id.in_(all_u_gids),
            models.ChatMessage.sender_id != user_id,
            models.ChatMessage.is_delivered == False
        ).update({"is_delivered": True})

    db.commit()

    return {"message": "online", "last_seen": now.isoformat()}


@app.get("/users/{user_id}/online")
def check_online(user_id: int, db: Session = Depends(get_db)):
    """Returns whether user is considered online (last_seen within 25 seconds)."""
    import datetime
    status = db.query(models.UserOnlineStatus).filter(
        models.UserOnlineStatus.user_id == user_id
    ).first()
    if not status:
        return {"is_online": False, "last_seen": None}
    now = datetime.datetime.utcnow()
    last = status.last_seen
    if last.tzinfo is not None:
        now = now.replace(tzinfo=datetime.timezone.utc)
    diff = (now - last).total_seconds()
    return {
        "is_online": diff <= 25,
        "last_seen": last.isoformat()
    }


def _fmt_timestamp(ts) -> str:
    """Format a datetime or ISO string to friendly string: Today→HH:MM, Yesterday→Yesterday, else Weekday."""
    if ts is None:
        return ""
    try:
        from datetime import datetime, timezone
        if isinstance(ts, str):
            ts = datetime.fromisoformat(ts.replace("Z", "+00:00"))
        now = datetime.now(timezone.utc)
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=timezone.utc)
        delta = (now.date() - ts.date()).days
        if delta == 0:
            return ts.strftime("%I:%M %p").lstrip("0")
        elif delta == 1:
            return "Yesterday"
        elif delta < 7:
            return ts.strftime("%A")
        else:
            return ts.strftime("%d %b")
    except Exception:
        return ""

# ─── Posts / Announcements Feature ──────────────────────────────────────────

class PostCreate(BaseModel):
    author_id: int
    text: Optional[str] = ""
    media_type: Optional[str] = None
    media_url: Optional[str] = None
    media_filename: Optional[str] = None
    poll_question: Optional[str] = None
    poll_options: Optional[List[str]] = None
    target_audience: Optional[str] = None  # "ACADEMIC" | "SIMPLE" | "ALL"

class PostLikeToggle(BaseModel):
    user_id: int
    liked: Optional[bool] = None

class PostSaveToggle(BaseModel):
    user_id: int
    saved: Optional[bool] = None

class PostCommentCreate(BaseModel):
    author_id: int
    text: str
    parent_id: Optional[int] = None

class PostPollVoteRequest(BaseModel):
    user_id: int
    option_id: str

def _relative_time(timestamp):
    diff = (datetime.datetime.utcnow() - timestamp).total_seconds()
    if diff < 60:
        return "Just now"
    if diff < 3600:
        return f"{int(diff // 60)}m ago"
    if diff < 86400:
        return f"{int(diff // 3600)}h ago"
    return f"{int(diff // 86400)}d ago"


def _post_audience(post: models.Post, author):
    audience = getattr(post, "target_audience", None)
    if audience:
        return audience
    role = (author.role or "").lower() if author else ""
    return "SIMPLE" if role in ["simple user", "simple_user", "simpleuser", "user"] else "ACADEMIC"


def _post_is_visible_in_active_mode(post: models.Post, user: models.User) -> bool:
    """Keep a mode-switched account inside its selected server-side audience."""
    audience = (_post_audience(post, None) or "").strip().upper()
    if _effective_session_mode(user) == "simple":
        return audience == "SIMPLE"
    return audience in {"ACADEMIC", "ALL"}


def _require_visible_post(post: models.Post | None, user: models.User) -> models.Post:
    if not post:
        raise HTTPException(status_code=404, detail="Post not found")
    if not _post_is_visible_in_active_mode(post, user):
        # Do not disclose whether a post exists in another session audience.
        raise HTTPException(status_code=404, detail="Post not found")
    return post


def _build_poll_data(post: models.Post, vote_counts=None, current_user_vote=None):
    if not post.poll_json:
        return None
    try:
        import json
        poll = json.loads(post.poll_json)
        options = []
        for opt in poll.get("options", []):
            option_id = str(opt.get("id"))
            options.append({
                "id": option_id,
                "text": opt.get("text", ""),
                "voteCount": (vote_counts or {}).get((post.id, option_id), 0),
                # Kept as an empty compatibility field for existing Android
                # Gson models; never ship every voter at feed scale.
                "voterUserIds": []
            })
        return {
            "question": poll.get("question", ""),
            "options": options,
            "totalVotes": sum(option["voteCount"] for option in options),
            "userVotedOptionId": current_user_vote
        }
    except Exception:
        return None


def _format_post_payload(post, author, like_count, liked_by_me, comment_count, saved_by_me, poll_data):
    author_name = author.name if author else f"User {post.author_id}"
    author_role = author.role if author else "Student"
    parts = []
    if author and author.degree: parts.append(author.degree)
    if author and author.major: parts.append(author.major)
    if author and author.academic_year: parts.append(f"– {author.academic_year}")
    media_obj = None
    if post.media_type and post.media_type != "NONE":
        media_obj = {
            "type": post.media_type,
            "mediaUrl": post.media_url,
            "localUri": None,
            "fileName": post.media_filename,
            "fileSizeText": None,
            "mimeType": None,
            "durationSeconds": 0,
            "pollData": poll_data
        }
    return {
        "id": str(post.id),
        "authorId": post.author_id,
        "authorName": author_name,
        "authorRole": author_role,
        "authorUsername": author.username if author else None,
        "authorProfilePic": author.profile_pic if author else None,
        "subtitleInfo": " ".join(parts) if parts else (author_role or "Student"),
        "text": post.text or "",
        "media": media_obj,
        "timestamp": post.timestamp.isoformat(),
        "formattedTime": _relative_time(post.timestamp),
        "likeCount": like_count,
        "likedByMe": liked_by_me,
        # Empty compatibility fields prevent older Gson clients from receiving
        # null lists while eliminating unbounded liker/saver payloads.
        "likedUserIds": [],
        "commentCount": comment_count,
        "comments": [],
        "savedByMe": saved_by_me,
        "savedUserIds": [],
        "reactionEmojis": ["👍", "❤️", "😮"],
        "targetAudience": _post_audience(post, author)
    }


def format_post(post: models.Post, current_user_id: int, db: Session):
    """Single-post formatter for mutation responses; feed reads use the batch formatter below."""
    from sqlalchemy import func
    author = db.query(models.User).filter(models.User.id == post.author_id).first()
    like_count = db.query(func.count(models.PostLike.id)).filter(models.PostLike.post_id == post.id).scalar() or 0
    comment_count = db.query(func.count(models.PostComment.id)).filter(models.PostComment.post_id == post.id).scalar() or 0
    liked_by_me = bool(current_user_id and db.query(models.PostLike.id).filter(
        models.PostLike.post_id == post.id, models.PostLike.user_id == current_user_id
    ).first())
    saved_by_me = bool(current_user_id and db.query(models.PostSave.id).filter(
        models.PostSave.post_id == post.id, models.PostSave.user_id == current_user_id
    ).first())
    vote_counts = {
        (post.id, str(option_id)): count for option_id, count in db.query(
            models.PostPollVote.option_id, func.count(models.PostPollVote.id)
        ).filter(models.PostPollVote.post_id == post.id).group_by(models.PostPollVote.option_id).all()
    }
    current_vote = None
    if current_user_id:
        vote = db.query(models.PostPollVote.option_id).filter(
            models.PostPollVote.post_id == post.id, models.PostPollVote.user_id == current_user_id
        ).first()
        current_vote = vote[0] if vote else None
    return _format_post_payload(post, author, like_count, liked_by_me, comment_count, saved_by_me,
                                _build_poll_data(post, vote_counts, current_vote))


def format_posts_batch(posts, current_user_id: int, db: Session):
    """Formats a feed with a fixed number of queries, regardless of post count."""
    if not posts:
        return []
    from sqlalchemy import func
    post_ids = [post.id for post in posts]
    author_ids = list({post.author_id for post in posts})
    authors = {user.id: user for user in db.query(models.User).filter(models.User.id.in_(author_ids)).all()}
    like_counts = dict(db.query(models.PostLike.post_id, func.count(models.PostLike.id)).filter(
        models.PostLike.post_id.in_(post_ids)
    ).group_by(models.PostLike.post_id).all())
    comment_counts = dict(db.query(models.PostComment.post_id, func.count(models.PostComment.id)).filter(
        models.PostComment.post_id.in_(post_ids)
    ).group_by(models.PostComment.post_id).all())
    liked_post_ids, saved_post_ids = set(), set()
    if current_user_id:
        liked_post_ids = {row[0] for row in db.query(models.PostLike.post_id).filter(
            models.PostLike.user_id == current_user_id, models.PostLike.post_id.in_(post_ids)
        ).all()}
        saved_post_ids = {row[0] for row in db.query(models.PostSave.post_id).filter(
            models.PostSave.user_id == current_user_id, models.PostSave.post_id.in_(post_ids)
        ).all()}
    poll_post_ids = [post.id for post in posts if post.poll_json]
    vote_counts, current_votes = {}, {}
    if poll_post_ids:
        vote_counts = {
            (post_id, str(option_id)): count for post_id, option_id, count in db.query(
                models.PostPollVote.post_id, models.PostPollVote.option_id, func.count(models.PostPollVote.id)
            ).filter(models.PostPollVote.post_id.in_(poll_post_ids)).group_by(
                models.PostPollVote.post_id, models.PostPollVote.option_id
            ).all()
        }
        if current_user_id:
            current_votes = dict(db.query(models.PostPollVote.post_id, models.PostPollVote.option_id).filter(
                models.PostPollVote.user_id == current_user_id,
                models.PostPollVote.post_id.in_(poll_post_ids)
            ).all())
    return [
        _format_post_payload(
            post, authors.get(post.author_id), like_counts.get(post.id, 0), post.id in liked_post_ids,
            comment_counts.get(post.id, 0), post.id in saved_post_ids,
            _build_poll_data(post, vote_counts, current_votes.get(post.id))
        ) for post in posts
    ]


def _format_comment_payload(comment, author, like_count, liked_by_me, replies):
    return {
        "id": str(comment.id),
        "postId": str(comment.post_id),
        "parentCommentId": str(comment.parent_id) if comment.parent_id else None,
        "authorId": comment.author_id,
        "authorName": author.name if author else f"User {comment.author_id}",
        "authorRole": author.role if author else "Student",
        "authorProfilePic": author.profile_pic if author else None,
        "text": comment.text,
        "timestamp": comment.timestamp.isoformat(),
        "formattedTime": _relative_time(comment.timestamp),
        "likesCount": like_count,
        "likedByMe": liked_by_me,
        "likedUserIds": [],
        "repliesCount": len(replies),
        "replies": replies
    }


def format_comment(comment: models.PostComment, current_user_id: int, db: Session, include_replies: bool = True):
    """Single-comment formatter for mutation responses; comment reads use the batch formatter."""
    from sqlalchemy import func
    author = db.query(models.User).filter(models.User.id == comment.author_id).first()
    like_count = db.query(func.count(models.PostCommentLike.id)).filter(
        models.PostCommentLike.comment_id == comment.id
    ).scalar() or 0
    liked_by_me = bool(current_user_id and db.query(models.PostCommentLike.id).filter(
        models.PostCommentLike.comment_id == comment.id,
        models.PostCommentLike.user_id == current_user_id
    ).first())
    replies = []
    if include_replies:
        children = db.query(models.PostComment).filter(models.PostComment.parent_id == comment.id).order_by(
            models.PostComment.timestamp.asc()
        ).all()
        replies = [format_comment(child, current_user_id, db, include_replies=False) for child in children]
    return _format_comment_payload(comment, author, like_count, liked_by_me, replies)


def format_comments_batch(comments, current_user_id: int, db: Session):
    """Formats top-level comments plus replies without per-comment database queries."""
    if not comments:
        return []
    from sqlalchemy import func
    comment_ids = [comment.id for comment in comments]
    author_ids = list({comment.author_id for comment in comments})
    authors = {user.id: user for user in db.query(models.User).filter(models.User.id.in_(author_ids)).all()}
    like_counts = dict(db.query(models.PostCommentLike.comment_id, func.count(models.PostCommentLike.id)).filter(
        models.PostCommentLike.comment_id.in_(comment_ids)
    ).group_by(models.PostCommentLike.comment_id).all())
    liked_comment_ids = set()
    if current_user_id:
        liked_comment_ids = {row[0] for row in db.query(models.PostCommentLike.comment_id).filter(
            models.PostCommentLike.user_id == current_user_id,
            models.PostCommentLike.comment_id.in_(comment_ids)
        ).all()}
    children_by_parent = {}
    for comment in comments:
        if comment.parent_id:
            children_by_parent.setdefault(comment.parent_id, []).append(comment)
    # Ensure replies are always in chronological order (oldest first), regardless
    # of the order the DB returned them.
    for parent_id in children_by_parent:
        children_by_parent[parent_id].sort(key=lambda c: (c.timestamp, c.id))
    top_level = [comment for comment in comments if not comment.parent_id]
    def render(comment):
        replies = [_format_comment_payload(child, authors.get(child.author_id), like_counts.get(child.id, 0),
                                            child.id in liked_comment_ids, [])
                   for child in children_by_parent.get(comment.id, [])]
        return _format_comment_payload(comment, authors.get(comment.author_id), like_counts.get(comment.id, 0),
                                       comment.id in liked_comment_ids, replies)
    return [render(comment) for comment in top_level]

@app.get("/posts")
def get_posts(
    user_id: Optional[int] = None,
    tab: Optional[int] = 0,
    role: Optional[str] = None,
    limit: int = Query(20, ge=1, le=50),
    before_timestamp: Optional[datetime.datetime] = None,
    before_id: Optional[int] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    from sqlalchemy import and_, func, or_
    query = db.query(models.Post)

    # `role` is retained for legacy callers without a user id, but it must never
    # decide the audience for an identified user: query parameters are client
    # controlled and would otherwise let a Simple User request the academic feed.
    if user_id is not None and user_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only request your own feed.")
    user_id = current_user.id
    caller_role = current_user.role

    # Role-Based Feed Isolation Enforced on Server (Requirement 1.2):
    # Server enforces audience on GET /posts using explicit target_audience column, NEVER text-sniffing.
    # - Simple User ONLY receives posts with target_audience == 'SIMPLE' (plus user's own posts)
    # - Teacher/Student/Admin ONLY receives posts with target_audience in ('ACADEMIC', 'ALL') (plus user's own posts)
    if _effective_session_mode(current_user) == "simple":
        query = query.filter(func.upper(models.Post.target_audience) == "SIMPLE")
    elif (caller_role or "").strip().lower() in ["teacher", "student", "admin", "faculty"]:
        query = query.filter(func.upper(models.Post.target_audience).in_(["ACADEMIC", "ALL"]))
    else:
        # Fail closed for an unexpected stored role.
        query = query.filter(models.Post.id == -1)

    if tab == 1 and user_id:
        query = query.filter(models.Post.author_id == user_id)
    elif tab == 2 and user_id:
        query = query.filter(models.Post.id.in_(
            db.query(models.PostSave.post_id).filter(models.PostSave.user_id == user_id)
        ))

    # Stable keyset cursor: timestamp alone can collide, so id is the tie-breaker.
    # Unlike OFFSET, its cost does not grow as the user scrolls deeper into a feed.
    if before_timestamp is not None:
        if before_id is not None:
            query = query.filter(or_(
                models.Post.timestamp < before_timestamp,
                and_(models.Post.timestamp == before_timestamp, models.Post.id < before_id)
            ))
        else:
            query = query.filter(models.Post.timestamp < before_timestamp)

    rows = query.order_by(models.Post.timestamp.desc(), models.Post.id.desc()).limit(limit + 1).all()
    page_posts = rows[:limit]
    has_more = len(rows) > limit
    last_post = page_posts[-1] if has_more and page_posts else None
    return {
        "posts": format_posts_batch(page_posts, user_id or 0, db),
        "nextCursorTimestamp": last_post.timestamp.isoformat() if last_post else None,
        "nextCursorId": last_post.id if last_post else None
    }

@app.post("/posts")
def create_post(
    data: PostCreate,
    idempotency_key: Optional[str] = Header(None, alias="Idempotency-Key"),
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    if data.author_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only create posts for your own account.")
    clean_key = "".join(c for c in (idempotency_key or "") if c.isalnum() or c in "-_")[:128]
    if clean_key:
        existing = db.query(models.Post).filter(models.Post.idempotency_key == clean_key).first()
        if existing:
            if existing.author_id != current_user.id:
                raise HTTPException(status_code=409, detail="This idempotency key belongs to another post.")
            return format_post(existing, data.author_id, db)

    poll_json_str = None
    if data.poll_question and data.poll_options:
        import json
        poll_obj = {
            "question": data.poll_question,
            "options": [{"id": f"opt_{i}", "text": opt} for i, opt in enumerate(data.poll_options) if opt.strip()]
        }
        poll_json_str = json.dumps(poll_obj)

    # Determine target audience
    # Client input cannot select another audience.  The stored mode decides.
    audience = "SIMPLE" if _effective_session_mode(current_user) == "simple" else "ACADEMIC"

    new_post = models.Post(
        author_id=data.author_id,
        target_audience=audience,
        text=data.text or "",
        media_type=data.media_type,
        media_url=data.media_url,
        media_filename=data.media_filename,
        poll_json=poll_json_str,
        idempotency_key=clean_key or None
    )
    db.add(new_post)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        if clean_key:
            existing = db.query(models.Post).filter(models.Post.idempotency_key == clean_key).first()
            if existing:
                if existing.author_id != current_user.id:
                    raise HTTPException(status_code=409, detail="This idempotency key belongs to another post.")
                return format_post(existing, data.author_id, db)
        raise
    db.refresh(new_post)
    return format_post(new_post, data.author_id, db)

@app.post("/posts/upload")
def create_post_with_media(
    author_id: int = Form(...),
    text: str = Form(""),
    media_type: str = Form("IMAGE"),
    target_audience: Optional[str] = Form(None),
    file: UploadFile = File(...),
    idempotency_key: Optional[str] = Header(None, alias="Idempotency-Key"),
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    if author_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only create posts for your own account.")
    clean_key = "".join(c for c in (idempotency_key or "") if c.isalnum() or c in "-_")[:128]
    if clean_key:
        existing = db.query(models.Post).filter(models.Post.idempotency_key == clean_key).first()
        if existing:
            if existing.author_id != current_user.id:
                raise HTTPException(status_code=409, detail="This idempotency key belongs to another post.")
            return format_post(existing, author_id, db)

    _enforce_video_upload_limit(file, media_type)
    media_url = save_and_upload_file(
        file, upload_dir=UPLOAD_DIR, folder="educonnect/posts", media_type=media_type
    )
    audience = "SIMPLE" if _effective_session_mode(current_user) == "simple" else "ACADEMIC"

    new_post = models.Post(
        author_id=author_id,
        target_audience=audience,
        text=text,
        media_type=media_type.upper(),
        media_url=media_url,
        media_filename=file.filename,
        idempotency_key=clean_key or None
    )
    db.add(new_post)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        if clean_key:
            existing = db.query(models.Post).filter(models.Post.idempotency_key == clean_key).first()
            if existing:
                if existing.author_id != current_user.id:
                    raise HTTPException(status_code=409, detail="This idempotency key belongs to another post.")
                return format_post(existing, author_id, db)
        raise
    db.refresh(new_post)
    return format_post(new_post, author_id, db)

@app.post("/posts/media/upload")
def upload_post_media(
    file: UploadFile = File(...),
    current_user: models.User = Depends(get_current_user)
):
    _enforce_video_upload_limit(file)
    media_url = save_and_upload_file(file, upload_dir=UPLOAD_DIR, folder="educonnect/media")
    return {
        "media_url": media_url,
        "fileName": file.filename,
        "message": "File uploaded successfully"
    }


@app.delete("/posts/{post_id}")
def delete_post(
    post_id: int,
    user_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    if user_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only delete your own posts")
    post = _require_visible_post(db.query(models.Post).filter(models.Post.id == post_id).first(), current_user)
    if post.author_id != current_user.id:
        raise HTTPException(status_code=403, detail="Not authorized to delete this post")
    
    db.query(models.PostLike).filter(models.PostLike.post_id == post_id).delete()
    db.query(models.PostSave).filter(models.PostSave.post_id == post_id).delete()
    db.query(models.PostComment).filter(models.PostComment.post_id == post_id).delete()
    db.query(models.PostPollVote).filter(models.PostPollVote.post_id == post_id).delete()
    db.delete(post)
    db.commit()
    return {"message": "Post deleted successfully"}

@app.post("/posts/{post_id}/like")
def toggle_post_like(
    post_id: int,
    data: PostLikeToggle,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    if data.user_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only update your own like.")
    post = _require_visible_post(db.query(models.Post).filter(models.Post.id == post_id).first(), current_user)
    
    existing = db.query(models.PostLike).filter(
        models.PostLike.post_id == post_id,
        models.PostLike.user_id == data.user_id
    ).first()
    
    if data.liked is not None:
        # Idempotent state enforcement: ensures rapid out-of-order taps never desync
        if data.liked:
            if not existing:
                db.add(models.PostLike(post_id=post_id, user_id=data.user_id))
                db.commit()
            liked = True
        else:
            if existing:
                db.delete(existing)
                db.commit()
            liked = False
    else:
        # Backward-compatible blind toggle
        if existing:
            db.delete(existing)
            liked = False
        else:
            db.add(models.PostLike(post_id=post_id, user_id=data.user_id))
            liked = True
        db.commit()
    
    total_likes = db.query(models.PostLike).filter(models.PostLike.post_id == post_id).count()
    return {"message": "Like updated", "liked": liked, "likeCount": total_likes}

@app.post("/posts/{post_id}/save")
def toggle_post_save(
    post_id: int,
    data: PostSaveToggle,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    if data.user_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only update your own saved posts.")
    post = _require_visible_post(db.query(models.Post).filter(models.Post.id == post_id).first(), current_user)
    
    existing = db.query(models.PostSave).filter(
        models.PostSave.post_id == post_id,
        models.PostSave.user_id == data.user_id
    ).first()
    
    if data.saved is not None:
        # Idempotent state enforcement
        if data.saved:
            if not existing:
                db.add(models.PostSave(post_id=post_id, user_id=data.user_id))
                db.commit()
            saved = True
        else:
            if existing:
                db.delete(existing)
                db.commit()
            saved = False
    else:
        # Backward-compatible blind toggle
        if existing:
            db.delete(existing)
            saved = False
        else:
            db.add(models.PostSave(post_id=post_id, user_id=data.user_id))
            saved = True
        db.commit()
    return {"message": "Save updated", "saved": saved}

@app.get("/posts/{post_id}/comments")
def get_post_comments(
    post_id: int,
    user_id: Optional[int] = None,
    limit: int = Query(20, ge=1, le=50),
    before_timestamp: Optional[datetime.datetime] = None,
    before_id: Optional[int] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Return a bounded, keyset-paginated page of top-level comments.

    Replies belonging to the page's parents are included so the existing nested
    reply UI remains complete.  Only top-level comments advance the cursor;
    otherwise a reply-heavy thread could make the page boundary unstable.
    """
    from sqlalchemy import or_, func
    if user_id is not None and user_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only request your own comments view.")
    user_id = current_user.id
    _require_visible_post(db.query(models.Post).filter(models.Post.id == post_id).first(), current_user)

    base_query = db.query(models.PostComment).filter(
        models.PostComment.post_id == post_id,
        models.PostComment.parent_id.is_(None)
    )
    if before_timestamp is not None:
        if before_id is not None:
            base_query = base_query.filter(or_(
                models.PostComment.timestamp < before_timestamp,
                (models.PostComment.timestamp == before_timestamp) & (models.PostComment.id < before_id)
            ))
        else:
            base_query = base_query.filter(models.PostComment.timestamp < before_timestamp)

    # Fetch newest first for an efficient keyset query, then return each page in
    # chronological order so the current Compose comments UI needs no rewrite.
    candidate_rows = base_query.order_by(
        models.PostComment.timestamp.desc(), models.PostComment.id.desc()
    ).limit(limit + 1).all()
    page_parents = candidate_rows[:limit]
    has_more = len(candidate_rows) > limit
    page_parents.reverse()

    parent_ids = [comment.id for comment in page_parents]
    replies = []
    if parent_ids:
        replies = db.query(models.PostComment).filter(
            models.PostComment.post_id == post_id,
            models.PostComment.parent_id.in_(parent_ids)
        ).order_by(models.PostComment.timestamp.asc(), models.PostComment.id.asc()).all()

    total_count = db.query(func.count(models.PostComment.id)).filter(
        models.PostComment.post_id == post_id
    ).scalar() or 0
    last_parent = page_parents[0] if has_more and page_parents else None
    return {
        "comments": format_comments_batch(page_parents + replies, user_id or 0, db),
        "nextCursorTimestamp": last_parent.timestamp.isoformat() if last_parent else None,
        "nextCursorId": last_parent.id if last_parent else None,
        "totalCount": total_count
    }

@app.post("/posts/{post_id}/comments")
def add_post_comment(
    post_id: int,
    data: PostCommentCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    if data.author_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only comment as your own account.")
    post = _require_visible_post(db.query(models.Post).filter(models.Post.id == post_id).first(), current_user)

    # Never accept a parent from another post (or a deleted/nonexistent parent).
    # This is the server-side backstop for the client optimistic-ID handoff.
    if data.parent_id is not None:
        parent = db.query(models.PostComment).filter(
            models.PostComment.id == data.parent_id,
            models.PostComment.post_id == post_id
        ).first()
        if not parent:
            raise HTTPException(status_code=422, detail="Parent comment does not belong to this post")
    
    new_comment = models.PostComment(
        post_id=post_id,
        parent_id=data.parent_id,
        author_id=data.author_id,
        text=data.text
    )
    db.add(new_comment)
    db.commit()
    db.refresh(new_comment)
    return format_comment(new_comment, data.author_id, db)

@app.post("/posts/comments/{comment_id}/like")
def toggle_comment_like(
    comment_id: int,
    data: PostLikeToggle,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    if data.user_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only update your own comment like.")
    comment = db.query(models.PostComment).filter(models.PostComment.id == comment_id).first()
    if not comment:
        raise HTTPException(status_code=404, detail="Comment not found")
    _require_visible_post(db.query(models.Post).filter(models.Post.id == comment.post_id).first(), current_user)

    existing = db.query(models.PostCommentLike).filter(
        models.PostCommentLike.comment_id == comment_id,
        models.PostCommentLike.user_id == data.user_id
    ).first()

    if data.liked is not None:
        if data.liked:
            if not existing:
                db.add(models.PostCommentLike(comment_id=comment_id, user_id=data.user_id))
                db.commit()
            liked = True
        else:
            if existing:
                db.delete(existing)
                db.commit()
            liked = False
    else:
        if existing:
            db.delete(existing)
            liked = False
        else:
            db.add(models.PostCommentLike(comment_id=comment_id, user_id=data.user_id))
            liked = True
        db.commit()

    total_likes = db.query(models.PostCommentLike).filter(models.PostCommentLike.comment_id == comment_id).count()
    return {"message": "Comment like updated", "liked": liked, "likeCount": total_likes}


@app.post("/posts/{post_id}/poll/vote")
def vote_post_poll(
    post_id: int,
    data: PostPollVoteRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    if data.user_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only vote as your own account.")
    post = _require_visible_post(db.query(models.Post).filter(models.Post.id == post_id).first(), current_user)
    if not post.poll_json:
        raise HTTPException(status_code=404, detail="Poll post not found")
    
    existing_vote = db.query(models.PostPollVote).filter(
        models.PostPollVote.post_id == post_id,
        models.PostPollVote.user_id == data.user_id
    ).first()
    
    if existing_vote:
        existing_vote.option_id = data.option_id
    else:
        new_vote = models.PostPollVote(
            post_id=post_id,
            user_id=data.user_id,
            option_id=data.option_id
        )
        db.add(new_vote)
    db.commit()
    return format_post(post, data.user_id, db)

@app.post("/admin/reset-database")
def admin_reset_database(secret: str):
    """Wipe all tables and uploaded files on Render server.
    Requires ADMIN_RESET_SECRET environment variable to be set on the server.
    If the env var is not set, this endpoint is permanently locked (403).
    """
    admin_secret = os.environ.get("ADMIN_RESET_SECRET", "")
    if not admin_secret or secret != admin_secret:
        raise HTTPException(status_code=403, detail="Unauthorized")

    models.Base.metadata.drop_all(bind=engine)
    models.Base.metadata.create_all(bind=engine)

    # Clean uploads folder
    if os.path.exists(UPLOAD_DIR):
        for filename in os.listdir(UPLOAD_DIR):
            file_path = os.path.join(UPLOAD_DIR, filename)
            try:
                if os.path.isfile(file_path) or os.path.islink(file_path):
                    os.unlink(file_path)
                elif os.path.isdir(file_path):
                    shutil.rmtree(file_path)
            except Exception:
                pass

    return {
        "status": "success",
        "message": "All users, chats, groups, assignments, and media files have been completely deleted."
    }
