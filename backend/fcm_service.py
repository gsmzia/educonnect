import os
import json
import logging
import datetime
import time
from typing import List

logger = logging.getLogger(__name__)

_firebase_initialized = False

# FCM data messages have a strict 4 KB payload limit.  Database profile pictures
# may be Base64-encoded images, which are far larger and cause Firebase to reject
# the *entire* message.  Keep push data deliberately compact; the app can load
# the full avatar after the user opens the conversation.
_FCM_TEXT_LIMIT = 1_200
_FCM_NAME_LIMIT = 160
_FCM_URL_LIMIT = 700
_FCM_AVATAR_URL_LIMIT = 512
_FCM_DATA_BUDGET_BYTES = 3_800
_FCM_MAX_ATTEMPTS = 3
_FCM_RETRY_BASE_SECONDS = 0.25

_FCM_MESSAGE_TYPES = {
    "text", "image", "voice", "video", "file", "audio", "system",
}
_FCM_ROLE_ALIASES = {
    "student": "Student",
    "teacher": "Teacher",
    "simple user": "Simple User",
    "simple_user": "Simple User",
    "simpleuser": "Simple User",
}


def _truncate_utf8(value, limit: int) -> str:
    """Return a valid UTF-8 string whose encoded size never exceeds *limit*."""
    cleaned = "".join(
        character
        for character in str(value or "")
        if character in ("\n", "\t") or ord(character) >= 32
    )
    raw = cleaned.encode("utf-8", errors="replace")
    if len(raw) <= limit:
        return raw.decode("utf-8")
    suffix = b"..."
    return raw[: max(0, limit - len(suffix))].decode("utf-8", errors="ignore") + suffix.decode("utf-8")


def _fcm_avatar_url(profile_pic) -> str:
    """FCM may carry only a small remote avatar URL, never raw/Base64 image data."""
    value = str(profile_pic or "").strip()
    if value.startswith(("https://", "http://")):
        return _truncate_utf8(value, _FCM_AVATAR_URL_LIMIT)
    return ""


def _fcm_media_url(media_url) -> str:
    """Keep only bounded web URLs that Android can safely request."""
    value = str(media_url or "").strip()
    if value.startswith(("https://", "http://")):
        return _truncate_utf8(value, _FCM_URL_LIMIT)
    return ""


def _safe_choice(value, allowed: set, fallback: str) -> str:
    normalized = str(value or "").strip().lower()
    return normalized if normalized in allowed else fallback


def _safe_role(value) -> str:
    normalized = " ".join(str(value or "").strip().lower().split())
    return _FCM_ROLE_ALIASES.get(normalized, "Student")


def _safe_integer_string(
    value,
    *,
    minimum: int = 0,
    maximum: int = 9_999_999_999_999_999_999,
) -> str:
    try:
        parsed = int(value)
    except (TypeError, ValueError, OverflowError):
        parsed = minimum
    return str(max(minimum, min(parsed, maximum)))


def _payload_size_bytes(payload: dict) -> int:
    """Conservatively count the serialized UTF-8 data object sent to FCM."""
    return len(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
            "utf-8", errors="replace"
        )
    )


def _compact_fcm_payload(data_payload: dict) -> dict:
    """Return a string-only, allow-listed payload safely below FCM's 4 KB limit."""
    compact = {
        "sender_id": _safe_integer_string(data_payload.get("sender_id")),
        "sender_name": _truncate_utf8(data_payload.get("sender_name"), _FCM_NAME_LIMIT),
        "text": _truncate_utf8(data_payload.get("text"), _FCM_TEXT_LIMIT),
        "message_id": _safe_integer_string(data_payload.get("message_id")),
        "is_group": (
            "true" if str(data_payload.get("is_group")).lower() == "true" else "false"
        ),
        "group_id": _safe_integer_string(data_payload.get("group_id")),
        "group_name": _truncate_utf8(data_payload.get("group_name"), _FCM_NAME_LIMIT),
        "role": _safe_role(data_payload.get("role")),
        "profile_pic": _fcm_avatar_url(data_payload.get("profile_pic")),
        "message_type": _safe_choice(
            data_payload.get("message_type"), _FCM_MESSAGE_TYPES, "text"
        ),
        "duration_sec": _safe_integer_string(
            data_payload.get("duration_sec"), maximum=86_400
        ),
        "media_url": _fcm_media_url(data_payload.get("media_url")),
        "timestamp": _truncate_utf8(data_payload.get("timestamp"), 64),
    }

    if _payload_size_bytes(compact) > _FCM_DATA_BUDGET_BYTES:
        # Keep routing fields intact and trim optional display data in priority
        # order. This is defensive: the per-field caps normally fit already.
        compact["text"] = _truncate_utf8(compact["text"], 512)
        compact["media_url"] = _truncate_utf8(compact["media_url"], 384)
        compact["profile_pic"] = _truncate_utf8(compact["profile_pic"], 256)
        compact["sender_name"] = _truncate_utf8(compact["sender_name"], 96)
        compact["group_name"] = _truncate_utf8(compact["group_name"], 96)

    if _payload_size_bytes(compact) > _FCM_DATA_BUDGET_BYTES:
        compact.update(
            text="New message",
            media_url="",
            profile_pic="",
            sender_name=_truncate_utf8(compact["sender_name"], 64),
            group_name=_truncate_utf8(compact["group_name"], 64),
        )

    # Fixed keys plus the caps above make this invariant deterministic. Raise
    # locally instead of sending an invalid payload that FCM would reject.
    if _payload_size_bytes(compact) > _FCM_DATA_BUDGET_BYTES:
        raise ValueError("FCM data payload exceeds the safe byte budget")
    return compact


def _is_transient_fcm_error(exc: Exception) -> bool:
    """Classify Firebase failures that are safe to retry briefly."""
    try:
        from firebase_admin import exceptions as firebase_exceptions

        transient_types = (
            firebase_exceptions.AbortedError,
            firebase_exceptions.CancelledError,
            firebase_exceptions.DeadlineExceededError,
            firebase_exceptions.InternalError,
            firebase_exceptions.ResourceExhaustedError,
            firebase_exceptions.UnavailableError,
            firebase_exceptions.UnknownError,
        )
        if isinstance(exc, transient_types):
            return True
    except (ImportError, AttributeError):
        pass

    code = str(getattr(exc, "code", "")).strip().lower().replace("_", "-")
    return code in {
        "aborted", "cancelled", "deadline-exceeded", "internal",
        "resource-exhausted", "unavailable", "unknown",
    }


def _is_permanent_token_error(messaging, exc: Exception) -> bool:
    """Return true only for errors that definitively identify a dead token."""
    return isinstance(
        exc,
        (messaging.UnregisteredError, messaging.SenderIdMismatchError),
    )


def _retry_delay(attempt: int) -> None:
    # These senders run as FastAPI background tasks, so this short backoff never
    # delays the message API response or blocks the event loop.
    time.sleep(_FCM_RETRY_BASE_SECONDS * (2 ** max(0, attempt - 1)))


def init_firebase():
    global _firebase_initialized
    if _firebase_initialized:
        return True

    try:
        import firebase_admin
        from firebase_admin import credentials

        # Uvicorn reloads and background tasks can import this module more than
        # once in one process. Reuse Firebase Admin's existing app instead of
        # treating its "already exists" exception as a push-delivery failure.
        if firebase_admin._apps:
            _firebase_initialized = True
            return True

        # 1. Try JSON string from environment variable (Best for Render / Cloud)
        creds_json = os.environ.get("FIREBASE_CREDENTIALS", "").strip()
        if creds_json:
            try:
                cred_dict = json.loads(creds_json)
                cred = credentials.Certificate(cred_dict)
                firebase_admin.initialize_app(cred)
                _firebase_initialized = True
                logger.info("[FCM] Initialized Firebase Admin from FIREBASE_CREDENTIALS env var.")
                return True
            except Exception as e:
                logger.error(f"[FCM] Failed to parse FIREBASE_CREDENTIALS: {e}")

        # 2. Try file path from environment variable
        creds_path = os.environ.get("FIREBASE_SERVICE_ACCOUNT_PATH", "").strip()
        if creds_path and os.path.exists(creds_path):
            cred = credentials.Certificate(creds_path)
            firebase_admin.initialize_app(cred)
            _firebase_initialized = True
            logger.info(f"[FCM] Initialized Firebase Admin from path: {creds_path}")
            return True

        # 3. Try default file in current directory
        local_key = os.path.join(os.path.dirname(__file__), "serviceAccountKey.json")
        if os.path.exists(local_key):
            cred = credentials.Certificate(local_key)
            firebase_admin.initialize_app(cred)
            _firebase_initialized = True
            logger.info(f"[FCM] Initialized Firebase Admin from local {local_key}")
            return True

        logger.warning("[FCM] No Firebase credentials found. Push notifications will be skipped until FIREBASE_CREDENTIALS is set.")
        return False
    except Exception as e:
        logger.error(f"[FCM] Firebase initialization error: {e}")
        return False

# Initialize on module import
init_firebase()

def _send_data_pushes(messaging, tokens: List[str], data_payload: dict):
    """Send 500-token batches with bounded retries and dead-token discovery."""
    tokens = list(dict.fromkeys(token for token in tokens if token))
    data_payload = _compact_fcm_payload(data_payload)
    successes = failures = 0
    stale_tokens = []
    for start in range(0, len(tokens), 500):
        pending = tokens[start:start + 500]
        for attempt in range(1, _FCM_MAX_ATTEMPTS + 1):
            try:
                response = messaging.send_each_for_multicast(
                    messaging.MulticastMessage(
                        data=data_payload,
                        tokens=pending,
                        android=messaging.AndroidConfig(
                            priority="high",
                            # Chat messages must be non-collapsible. If a phone
                            # is offline, FCM queues each message and Android
                            # groups them by conversation locally.
                            ttl=datetime.timedelta(weeks=4),
                        ),
                    )
                )
            except Exception as exc:
                if _is_transient_fcm_error(exc) and attempt < _FCM_MAX_ATTEMPTS:
                    logger.warning(
                        "[FCM] Transient batch failure for %s device(s); retry %s/%s (%s)",
                        len(pending), attempt + 1, _FCM_MAX_ATTEMPTS,
                        type(exc).__name__,
                    )
                    _retry_delay(attempt)
                    continue

                failures += len(pending)
                logger.warning(
                    "[FCM] Batch delivery stopped for %s device(s) after attempt %s (%s)",
                    len(pending), attempt, type(exc).__name__,
                )
                break

            retry_tokens = []
            response_items = list(response.responses)
            for index, token in enumerate(pending):
                result = response_items[index] if index < len(response_items) else None
                if result is not None and result.success:
                    successes += 1
                    continue

                exc = result.exception if result is not None else None
                if exc is not None and _is_permanent_token_error(messaging, exc):
                    stale_tokens.append(token)
                    failures += 1
                elif (
                    (result is None or _is_transient_fcm_error(exc))
                    and attempt < _FCM_MAX_ATTEMPTS
                ):
                    retry_tokens.append(token)
                else:
                    failures += 1

            if not retry_tokens:
                break

            logger.warning(
                "[FCM] Retrying %s transient device delivery failure(s), attempt %s/%s",
                len(retry_tokens), attempt + 1, _FCM_MAX_ATTEMPTS,
            )
            pending = retry_tokens
            _retry_delay(attempt)

    return successes, failures, list(dict.fromkeys(stale_tokens))


def send_personal_fcm_push(
    sender_id: int,
    receiver_id: int,
    message_id: int,
    text: str,
    message_type: str = "text",
    duration_sec: int = 0,
    media_url: str = ""
):
    """
    Sends an instant high-priority FCM push notification for a direct personal message.
    """
    if not init_firebase():
        return

    import models
    from database import SessionLocal
    from firebase_admin import messaging

    db = SessionLocal()
    try:
        token_records = db.query(models.UserFcmToken).filter(models.UserFcmToken.user_id == receiver_id).all()
        tokens = [rec.token for rec in token_records if rec.token]
        if not tokens:
            logger.warning("[FCM] No registered device token for personal receiver %s", receiver_id)
            return

        sender = db.query(models.User).filter(models.User.id == sender_id).first()
        sender_name = sender.name if sender else "New Message"
        sender_role = sender.role if (sender and sender.role) else "Student"
        sender_pic = sender.profile_pic if (sender and sender.profile_pic) else ""

        data_payload = {
            "sender_id": str(sender_id),
            "sender_name": _truncate_utf8(sender_name, _FCM_NAME_LIMIT),
            "text": _truncate_utf8(text, _FCM_TEXT_LIMIT),
            "message_id": str(message_id),
            "is_group": "false",
            "group_id": "0",
            "group_name": "",
            "role": str(sender_role),
            "profile_pic": _fcm_avatar_url(sender_pic),
            "message_type": str(message_type or "text"),
            "duration_sec": str(duration_sec or 0),
            "media_url": _truncate_utf8(media_url, _FCM_URL_LIMIT),
            "timestamp": datetime.datetime.utcnow().isoformat()
        }

        success_count, failure_count, stale_tokens = _send_data_pushes(
            messaging, tokens, data_payload
        )
        logger.info(
            "[FCM] Push attempted for %s device(s): success %s, fail %s",
            len(set(tokens)), success_count, failure_count,
        )

        if stale_tokens:
            db.query(models.UserFcmToken).filter(
                models.UserFcmToken.token.in_(stale_tokens)
            ).delete(synchronize_session=False)
            db.commit()
            logger.info(f"[FCM] Pruned {len(stale_tokens)} stale FCM token(s) for receiver {receiver_id}")
    except Exception as exc:
        logger.warning(
            "[FCM] Failed to send push to receiver %s (%s)",
            receiver_id,
            type(exc).__name__,
        )
    finally:
        db.close()


def send_group_fcm_push(
    sender_id: int,
    group_id: int,
    message_id: int,
    text: str,
    message_type: str = "text",
    duration_sec: int = 0,
    media_url: str = ""
):
    """
    Sends instant high-priority FCM push notifications to all group members (excluding sender).
    Automatically prunes dead/unregistered/expired tokens from the DB.
    """
    if not init_firebase():
        return

    import models
    from database import SessionLocal
    from firebase_admin import messaging

    db = SessionLocal()
    try:
        group = db.query(models.Group).filter(models.Group.id == group_id).first()
        if not group:
            return

        group_name = group.name or f"{group.degree} {group.major} {group.academic_year}"

        # Collect all member IDs in this group except the sender
        member_links = db.query(models.GroupStudent).filter(
            models.GroupStudent.group_id == group_id,
            (models.GroupStudent.is_left == False) | (models.GroupStudent.is_left == None),
        ).all()
        target_user_ids = {link.student_id for link in member_links if link.student_id != sender_id}
        if group.teacher_id and group.teacher_id != sender_id:
            target_user_ids.add(group.teacher_id)

        if not target_user_ids:
            return

        # Fetch FCM tokens for these members (supports multi-device)
        token_records = db.query(models.UserFcmToken).filter(
            models.UserFcmToken.user_id.in_(target_user_ids)
        ).all()

        tokens = [rec.token for rec in token_records if rec.token]
        if not tokens:
            logger.warning("[FCM] No registered device token for group %s recipients", group_id)
            return

        sender = db.query(models.User).filter(models.User.id == sender_id).first()
        sender_name = sender.name if sender else "New Message"
        sender_role = sender.role if (sender and sender.role) else "Student"
        sender_pic = sender.profile_pic if (sender and sender.profile_pic) else ""

        data_payload = {
            "sender_id": str(sender_id),
            "sender_name": _truncate_utf8(sender_name, _FCM_NAME_LIMIT),
            "text": _truncate_utf8(text, _FCM_TEXT_LIMIT),
            "message_id": str(message_id),
            "is_group": "true",
            "group_id": str(group_id),
            "group_name": _truncate_utf8(group_name, _FCM_NAME_LIMIT),
            "role": str(sender_role),
            "profile_pic": _fcm_avatar_url(sender_pic),
            "message_type": str(message_type or "text"),
            "duration_sec": str(duration_sec or 0),
            "media_url": _truncate_utf8(media_url, _FCM_URL_LIMIT),
            "timestamp": datetime.datetime.utcnow().isoformat()
        }

        success_count, failure_count, stale_tokens = _send_data_pushes(
            messaging, tokens, data_payload
        )
        logger.info(
            "[FCM] Push attempted for %s device(s): success %s, fail %s",
            len(set(tokens)), success_count, failure_count,
        )

        if stale_tokens:
            db.query(models.UserFcmToken).filter(
                models.UserFcmToken.token.in_(stale_tokens)
            ).delete(synchronize_session=False)
            db.commit()
            logger.info(f"[FCM] Pruned {len(stale_tokens)} stale FCM token(s) from group {group_id}")
    except Exception as exc:
        logger.warning(
            "[FCM] Failed to send group push for group %s (%s)",
            group_id,
            type(exc).__name__,
        )
    finally:
        db.close()
