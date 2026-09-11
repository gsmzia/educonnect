from sqlalchemy import Column, Integer, BigInteger, String, ForeignKey, DateTime, Text, Boolean, UniqueConstraint
from sqlalchemy.orm import relationship
import datetime
from database import Base

class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    email = Column(String, unique=True, index=True)
    username = Column(String, unique=True, index=True, nullable=False)
    password = Column(String)
    name = Column(String)
    role = Column(String)
    degree = Column(String, nullable=True)
    major = Column(String, nullable=True)
    academic_year = Column(String, nullable=True)
    profile_pic = Column(String, nullable=True)
    # ── Multi-Tenant Academic fields ──────────────────────────────────────────
    university_id = Column(Integer, ForeignKey("universities.id"), nullable=True, index=True)
    is_founder = Column(Boolean, default=False)
    semester = Column(Integer, nullable=True)
    # A verified academic account may enter the isolated social/simple space
    # without changing its immutable academic role or university membership.
    active_session_mode = Column(String, nullable=False, default="academic")

class OTP(Base):
    __tablename__ = "otps"

    id = Column(Integer, primary_key=True, index=True)
    email = Column(String, unique=True, index=True)
    otp_code = Column(String)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

class Group(Base):
    __tablename__ = "groups"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String)
    degree = Column(String, nullable=True)
    major = Column(String, nullable=True)
    academic_year = Column(String, nullable=True)
    subject_id = Column(Integer, nullable=True)
    teacher_id = Column(Integer, ForeignKey("users.id"), nullable=True)
    profile_pic = Column(String, nullable=True)
    # ── Multi-Tenant Academic links ───────────────────────────────────────────
    university_id = Column(Integer, ForeignKey("universities.id", ondelete="CASCADE"), nullable=True, index=True)
    academic_subject_id = Column(Integer, ForeignKey("academic_subjects.id", ondelete="SET NULL"), nullable=True, index=True)
    academic_semester_id = Column(Integer, ForeignKey("academic_semesters.id", ondelete="SET NULL"), nullable=True, index=True)

    # One official group represents one subject at one university.  The
    # nullable columns keep legacy/manual groups compatible while protecting
    # newly generated academic groups from duplicate concurrent setup calls.
    __table_args__ = (
        UniqueConstraint('university_id', 'academic_subject_id', name='uq_official_group_subject'),
    )

# ── Multi-Tenant University & Academic Hierarchy ──────────────────────────────

class University(Base):
    """
    Top-level academic tenant.
    Every campus has a unique, verified uni_key (e.g. EDU-PK-NUST-8291).
    All programs, departments, semesters, subjects, teachers, and students link to this.
    """
    __tablename__ = "universities"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, nullable=False, index=True)
    country = Column(String, nullable=False, index=True)
    uni_key = Column(String, unique=True, nullable=False, index=True)
    founder_id = Column(Integer, ForeignKey("users.id"), nullable=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    # ── Founder Email OTP & Admin Review Workflow ──────────────────────────────
    # status values:
    #   "pending_email_verification"  → Founder registered but hasn't verified email yet
    #   "pending_admin_review"        → Email OTP verified, waiting for admin to approve
    #   "verified"                    → Admin approved — key is now distributable
    #   "rejected"                    → Admin rejected the application
    founder_email = Column(String, nullable=True)
    founder_otp = Column(String, nullable=True)
    founder_otp_expires_at = Column(DateTime, nullable=True)
    status = Column(String, default="pending_email_verification")


class AcademicProgram(Base):
    """
    Degrees/Programs offered by a university (e.g. BS, MS, PhD, or custom).
    """
    __tablename__ = "academic_programs"

    id = Column(Integer, primary_key=True, index=True)
    university_id = Column(Integer, ForeignKey("universities.id", ondelete="CASCADE"), nullable=False, index=True)
    name = Column(String, nullable=False)

    __table_args__ = (
        UniqueConstraint('university_id', 'name', name='uq_uni_program'),
    )


class AcademicDepartment(Base):
    """
    Departments under a program (e.g. Computer Science, Software Engineering, BBA).
    Stores total_semesters (e.g. 8 for BS, 4 for MS).
    """
    __tablename__ = "academic_departments"

    id = Column(Integer, primary_key=True, index=True)
    program_id = Column(Integer, ForeignKey("academic_programs.id", ondelete="CASCADE"), nullable=False, index=True)
    name = Column(String, nullable=False)
    total_semesters = Column(Integer, default=8, nullable=False)

    __table_args__ = (
        UniqueConstraint('program_id', 'name', name='uq_program_dept'),
    )


class AcademicSemester(Base):
    """
    A specific semester within a department (1, 2, 3... total_semesters).
    """
    __tablename__ = "academic_semesters"

    id = Column(Integer, primary_key=True, index=True)
    department_id = Column(Integer, ForeignKey("academic_departments.id", ondelete="CASCADE"), nullable=False, index=True)
    semester_num = Column(Integer, nullable=False)

    __table_args__ = (
        UniqueConstraint('department_id', 'semester_num', name='uq_dept_semester'),
    )


class AcademicSubject(Base):
    """
    An official curriculum subject taught in a specific semester.
    """
    __tablename__ = "academic_subjects"

    id = Column(Integer, primary_key=True, index=True)
    semester_id = Column(Integer, ForeignKey("academic_semesters.id", ondelete="CASCADE"), nullable=False, index=True)
    name = Column(String, nullable=False)
    code = Column(String, nullable=True)

    __table_args__ = (
        UniqueConstraint('semester_id', 'name', name='uq_semester_subject'),
    )


class GroupStudent(Base):
    __tablename__ = "group_students"

    id = Column(Integer, primary_key=True, index=True)
    group_id = Column(Integer, ForeignKey("groups.id"))
    student_id = Column(Integer, ForeignKey("users.id"))
    is_left = Column(Boolean, default=False)
    left_at = Column(DateTime, nullable=True)

    __table_args__ = (
        UniqueConstraint('group_id', 'student_id', name='uq_group_student_membership'),
    )

class ChatMessage(Base):
    __tablename__ = "chat_messages"

    id = Column(Integer, primary_key=True, index=True)
    group_id = Column(Integer, ForeignKey("groups.id"))
    sender_id = Column(Integer, ForeignKey("users.id"))
    sender_name = Column(String)
    text = Column(Text)
    message_type = Column(String, default="text")
    media_url = Column(String, nullable=True)
    # Provider identity is kept server-side only; it lets the lifecycle worker
    # delete precisely one Cloudinary asset without parsing a delivery URL.
    media_public_id = Column(String, nullable=True, index=True)
    media_resource_type = Column(String, nullable=True)
    is_media_expired = Column(Boolean, default=False, nullable=False)
    media_downloaded_at = Column(DateTime, nullable=True)
    timestamp = Column(DateTime, default=datetime.datetime.utcnow)
    is_delivered = Column(Boolean, default=False)  # any group member online after send
    is_read = Column(Boolean, default=False)        # any other member opened chat
    is_deleted = Column(Boolean, default=False)
    deleted_by = Column(Integer, nullable=True)
    is_pinned = Column(Boolean, default=False)
    pinned_at = Column(DateTime, nullable=True)
    idempotency_key = Column(String, nullable=True, index=True)
    duration_sec = Column(Integer, default=0)

class PersonalMessage(Base):
    __tablename__ = "personal_messages"

    id = Column(Integer, primary_key=True, index=True)
    sender_id = Column(Integer, ForeignKey("users.id"))
    receiver_id = Column(Integer, ForeignKey("users.id"))
    text = Column(Text)
    message_type = Column(String, default="text")
    media_url = Column(String, nullable=True)
    media_public_id = Column(String, nullable=True, index=True)
    media_resource_type = Column(String, nullable=True)
    is_media_expired = Column(Boolean, default=False, nullable=False)
    media_downloaded_at = Column(DateTime, nullable=True)
    timestamp = Column(DateTime, default=datetime.datetime.utcnow)
    is_read = Column(Boolean, default=False)
    is_delivered = Column(Boolean, default=False)  # receiver came online after send
    is_deleted = Column(Boolean, default=False)
    deleted_by = Column(Integer, nullable=True)
    is_pinned = Column(Boolean, default=False)
    pinned_at = Column(DateTime, nullable=True)
    idempotency_key = Column(String, nullable=True, index=True)
    duration_sec = Column(Integer, default=0)


class PersonalBlock(Base):
    """One-way personal-chat block. A chat is blocked when either user has a row."""
    __tablename__ = "personal_blocks"

    id = Column(Integer, primary_key=True, index=True)
    blocker_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    blocked_user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow, nullable=False)

    __table_args__ = (
        UniqueConstraint('blocker_id', 'blocked_user_id', name='uq_personal_block_pair'),
    )


class PersonalChatClearState(Base):
    """Per-user clear marker; never deletes the other participant's history."""
    __tablename__ = "personal_chat_clear_states"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    other_user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    cleared_through_message_id = Column(Integer, nullable=False, default=0)
    updated_at = Column(DateTime, default=datetime.datetime.utcnow, nullable=False)

    __table_args__ = (
        UniqueConstraint('user_id', 'other_user_id', name='uq_personal_chat_clear_state'),
    )


class GroupChatClearState(Base):
    """Per-member group clear marker; group history remains intact for others."""
    __tablename__ = "group_chat_clear_states"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    group_id = Column(Integer, ForeignKey("groups.id"), nullable=False, index=True)
    cleared_through_message_id = Column(Integer, nullable=False, default=0)
    updated_at = Column(DateTime, default=datetime.datetime.utcnow, nullable=False)

    __table_args__ = (
        UniqueConstraint('user_id', 'group_id', name='uq_group_chat_clear_state'),
    )


class ResumableUpload(Base):
    """Server-side checkpoint for media uploads paused on a mobile device."""
    __tablename__ = "resumable_uploads"

    id = Column(Integer, primary_key=True, index=True)
    transfer_id = Column(String, unique=True, nullable=False, index=True)
    chat_type = Column(String, nullable=False)  # personal | group | post
    sender_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    peer_id = Column(Integer, nullable=False)   # receiver or group
    text = Column(Text, default="")
    message_type = Column(String, default="file")
    duration_sec = Column(Integer, default=0)
    file_name = Column(String, nullable=False)
    total_bytes = Column(BigInteger, nullable=False)
    received_bytes = Column(BigInteger, default=0)
    temp_path = Column(String, nullable=False)
    # Post-media uploads finish before the user submits the post.  Retaining
    # the resulting URL makes a lost final acknowledgement idempotent.
    media_url = Column(String, nullable=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow, nullable=False)
    updated_at = Column(DateTime, default=datetime.datetime.utcnow, nullable=False)

class GroupReadStatus(Base):
    __tablename__ = "group_read_statuses"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"))
    group_id = Column(Integer, ForeignKey("groups.id"))
    last_read_message_id = Column(Integer, default=0)
    last_read_at = Column(DateTime, default=datetime.datetime.utcnow)

    __table_args__ = (
        UniqueConstraint('user_id', 'group_id', name='uq_user_group_read_status'),
    )

class UserOnlineStatus(Base):
    __tablename__ = "user_online_statuses"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), unique=True, index=True)
    last_seen = Column(DateTime, default=datetime.datetime.utcnow)
    # A user is considered "online" if last_seen is within 30 seconds

# ── FCM Push Notification Token Store ─────────────────────────────────────────
class UserFcmToken(Base):
    """
    Stores Firebase Cloud Messaging (FCM) device tokens.
    Unique on token: one physical device can have one active token,
    while a single user can have multiple devices (phone, tablet, etc.).
    """
    __tablename__ = "user_fcm_tokens"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), index=True)
    token = Column(String, unique=True, index=True, nullable=False)
    platform = Column(String, default="android")   # "android" | "ios"
    updated_at = Column(DateTime, default=datetime.datetime.utcnow)

# ── Posts / Announcements ──────────────────────────────────────────────────────
class Post(Base):
    """
    A user-created post/announcement visible to all users on the feed.
    Supports plain text, image/video/document media, and polls.
    """
    __tablename__ = "posts"

    id          = Column(Integer, primary_key=True, index=True)
    author_id   = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    target_audience = Column(String, default="ACADEMIC", nullable=False, index=True)  # "ACADEMIC" | "SIMPLE" | "ALL"
    text        = Column(Text, default="")
    # Media attachment (optional)
    media_type  = Column(String, nullable=True)   # "image"|"video"|"document"|"poll"|None
    media_url   = Column(String, nullable=True)
    media_filename = Column(String, nullable=True)
    # Poll (optional — stored as JSON text if media_type == "poll")
    poll_json   = Column(Text, nullable=True)      # JSON: {question, options:[{id,text}]}
    timestamp   = Column(DateTime, default=datetime.datetime.utcnow, index=True)
    # The database must enforce this too: the pre-insert lookup alone cannot
    # prevent two concurrent retries from creating two posts.
    idempotency_key = Column(String, nullable=True, unique=True, index=True)

class PostLike(Base):
    """One row per (user, post) pair — acts as a like toggle."""
    __tablename__ = "post_likes"

    id      = Column(Integer, primary_key=True, index=True)
    post_id = Column(Integer, ForeignKey("posts.id", ondelete="CASCADE"), nullable=False)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)

    __table_args__ = (
        UniqueConstraint("post_id", "user_id", name="uq_post_like"),
    )

class PostSave(Base):
    """One row per (user, post) pair — bookmarks/saves."""
    __tablename__ = "post_saves"

    id      = Column(Integer, primary_key=True, index=True)
    post_id = Column(Integer, ForeignKey("posts.id", ondelete="CASCADE"), nullable=False)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)

    __table_args__ = (
        UniqueConstraint("post_id", "user_id", name="uq_post_save"),
    )

class PostComment(Base):
    """A comment on a post (supports top-level comments and nested replies via parent_id)."""
    __tablename__ = "post_comments"

    id        = Column(Integer, primary_key=True, index=True)
    post_id   = Column(Integer, ForeignKey("posts.id", ondelete="CASCADE"), nullable=False, index=True)
    parent_id = Column(Integer, ForeignKey("post_comments.id", ondelete="CASCADE"), nullable=True, index=True)
    author_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    text      = Column(Text, nullable=False)
    timestamp = Column(DateTime, default=datetime.datetime.utcnow)

class PostCommentLike(Base):
    """One row per (user, comment) pair — acts as a comment like toggle."""
    __tablename__ = "post_comment_likes"

    id         = Column(Integer, primary_key=True, index=True)
    comment_id = Column(Integer, ForeignKey("post_comments.id", ondelete="CASCADE"), nullable=False, index=True)
    user_id    = Column(Integer, ForeignKey("users.id"), nullable=False)

    __table_args__ = (
        UniqueConstraint("comment_id", "user_id", name="uq_post_comment_like"),
    )

class PostPollVote(Base):
    """One row per (user, post) — stores which poll option the user voted for."""
    __tablename__ = "post_poll_votes"

    id        = Column(Integer, primary_key=True, index=True)
    post_id   = Column(Integer, ForeignKey("posts.id", ondelete="CASCADE"), nullable=False)
    user_id   = Column(Integer, ForeignKey("users.id"), nullable=False)
    option_id = Column(String, nullable=False)   # matches PollOption.id in poll_json

    __table_args__ = (
        UniqueConstraint("post_id", "user_id", name="uq_post_poll_vote"),
    )


class Assignment(Base):
    __tablename__ = 'assignments'
    id = Column(Integer, primary_key=True, index=True)
    group_id = Column(Integer, ForeignKey('groups.id'))
    title = Column(String)
    description = Column(String)
    file_url = Column(String)

class Attendance(Base):
    __tablename__ = 'attendance'
    id = Column(Integer, primary_key=True, index=True)
    student_id = Column(Integer, ForeignKey('users.id'))
    group_id = Column(Integer, ForeignKey('groups.id'))
    date = Column(String)
    status = Column(String)
