"""Small, dependency-free signed-session helpers for protected API routes.

The app already sends ``Authorization: Bearer …`` through ``ApiClient``.  This
module makes that header meaningful without introducing a second auth service.
Only an HMAC signature is stored in the token; user data remains in PostgreSQL.
"""

import base64
import binascii
import hashlib
import hmac
import json
import os
import time
from typing import Optional

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy.orm import Session

from database import get_db
import models


def _session_secret() -> bytes:
    configured = os.environ.get("AUTH_SESSION_SECRET", "").strip()
    if configured:
        return configured.encode("utf-8")

    # Local development remains usable, but a production deployment must set a
    # real secret.  A predictable production fallback would make every account
    # forgeable, so fail closed there instead.
    environment = os.environ.get("ENVIRONMENT", "development").strip().lower()
    if environment in {"production", "prod"}:
        raise RuntimeError("AUTH_SESSION_SECRET must be configured in production")
    return b"development-only-change-me-before-production"


def _b64_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _b64_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def issue_access_token(user_id: int) -> str:
    """Create a signed, expiring session token for one user."""
    try:
        ttl_seconds = max(300, min(int(os.environ.get("AUTH_SESSION_TTL_SECONDS", 2_592_000)), 7_776_000))
    except (TypeError, ValueError):
        ttl_seconds = 2_592_000  # 30 days

    now = int(time.time())
    payload = json.dumps(
        {"sub": user_id, "iat": now, "exp": now + ttl_seconds},
        separators=(",", ":"),
    ).encode("utf-8")
    encoded_payload = _b64_encode(payload)
    signature = hmac.new(
        _session_secret(), encoded_payload.encode("ascii"), hashlib.sha256
    ).digest()
    return f"v1.{encoded_payload}.{_b64_encode(signature)}"


def _token_user_id(token: str) -> Optional[int]:
    try:
        version, encoded_payload, encoded_signature = token.split(".", 2)
        if version != "v1":
            return None
        expected_signature = hmac.new(
            _session_secret(), encoded_payload.encode("ascii"), hashlib.sha256
        ).digest()
        if not hmac.compare_digest(expected_signature, _b64_decode(encoded_signature)):
            return None
        payload = json.loads(_b64_decode(encoded_payload).decode("utf-8"))
        user_id = int(payload["sub"])
        if user_id <= 0 or int(payload["exp"]) <= int(time.time()):
            return None
        return user_id
    except (KeyError, TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError, binascii.Error):
        return None


def get_current_user(
    authorization: Optional[str] = Header(default=None),
    db: Session = Depends(get_db),
) -> models.User:
    """Resolve the current user from a valid Bearer session, or return 401."""
    if not authorization:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Sign in is required.")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="A valid Bearer session is required.")
    user_id = _token_user_id(token.strip())
    if user_id is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Your session has expired. Please sign in again.")
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Your account is no longer available.")
    return user
