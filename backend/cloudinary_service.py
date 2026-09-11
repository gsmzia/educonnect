import os
import shutil
import uuid
import logging
from dataclasses import dataclass
import cloudinary
import cloudinary.uploader

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class UploadedMediaAsset:
    url: str
    public_id: str | None = None
    resource_type: str | None = None


def destroy_asset(public_id: str, resource_type: str | None) -> bool:
    """Delete one known Cloudinary asset; never infer identifiers from URLs."""
    if not public_id or not configure_cloudinary():
        return False
    try:
        result = cloudinary.uploader.destroy(
            public_id,
            resource_type=(resource_type or "image"),
            invalidate=True,
        )
        return result.get("result") in {"ok", "not found"}
    except Exception as exc:
        logger.warning("Cloudinary deletion failed for %s: %s", public_id, exc)
        return False

# Cloudinary creates the HLS renditions asynchronously.  The original MP4 is
# still delivered immediately, so clients can safely fall back while the first
# adaptive playlist is being prepared.
_HLS_EAGER_TRANSFORMATION = [{"streaming_profile": "auto", "format": "m3u8"}]


def _cloudinary_required() -> bool:
    return os.environ.get("REQUIRE_CLOUDINARY", "false").strip().lower() in {"1", "true", "yes"}


def _is_video(filename: str, content_type: str = "", media_type: str = "") -> bool:
    """Classify video conservatively without reading an upload into memory."""
    if (media_type or "").strip().lower() == "video":
        return True
    if (content_type or "").strip().lower().startswith("video/"):
        return True
    extension = os.path.splitext(filename or "")[1].lower()
    return extension in {".mp4", ".m4v", ".mov", ".mkv", ".webm", ".3gp", ".avi"}


def _cloudinary_upload_options(local_path: str, folder: str, *, is_video: bool) -> dict:
    """Return provider options without changing the existing local fallback path."""
    options = {
        "resource_type": "video" if is_video else "auto",
        "folder": folder,
        "use_filename": True,
        "unique_filename": True,
    }
    return options


def _asset_resource_type(upload_result: dict, filename: str, is_video: bool) -> str:
    """Keep an explicit deletion type even if a provider omits it in a response."""
    reported = (upload_result.get("resource_type") or "").strip().lower()
    if reported:
        return reported
    if is_video:
        return "video"
    extension = os.path.splitext(filename or "")[1].lower()
    return "image" if extension in {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic"} else "raw"


def _queue_adaptive_streaming(upload_result: dict) -> None:
    """Ask Cloudinary to pre-create HLS without making the original upload fail."""
    public_id = (upload_result.get("public_id") or "").strip()
    if not public_id:
        return
    options = {
        "resource_type": "video",
        "type": "upload",
        "eager": _HLS_EAGER_TRANSFORMATION,
        "eager_async": True,
    }
    notification_url = os.environ.get("CLOUDINARY_EAGER_NOTIFICATION_URL", "").strip()
    if notification_url:
        options["eager_notification_url"] = notification_url
    try:
        cloudinary.uploader.explicit(public_id, **options)
    except Exception as exc:
        # The original secure_url is already durable.  HLS can be generated on
        # demand later, so a plan/configuration limitation must not turn a
        # successful user upload into a failed one.
        logger.warning("Cloudinary HLS pre-generation skipped for %s: %s", public_id, exc)

def is_cloudinary_configured() -> bool:
    if os.environ.get("CLOUDINARY_URL", "").strip():
        return True
    name = os.environ.get("CLOUDINARY_CLOUD_NAME", "").strip()
    key = os.environ.get("CLOUDINARY_API_KEY", "").strip()
    secret = os.environ.get("CLOUDINARY_API_SECRET", "").strip()
    return bool(name and key and secret)

def configure_cloudinary():
    cloudinary_url = os.environ.get("CLOUDINARY_URL", "").strip()
    if cloudinary_url:
        # If user pasted the whole CLOUDINARY_URL=... string
        if cloudinary_url.startswith("CLOUDINARY_URL="):
            cloudinary_url = cloudinary_url.replace("CLOUDINARY_URL=", "", 1).strip()
        cloudinary.config(cloudinary_url=cloudinary_url, secure=True)
        return True

    name = os.environ.get("CLOUDINARY_CLOUD_NAME", "").strip()
    key = os.environ.get("CLOUDINARY_API_KEY", "").strip()
    secret = os.environ.get("CLOUDINARY_API_SECRET", "").strip()
    if name and key and secret:
        cloudinary.config(
            cloud_name=name,
            api_key=key,
            api_secret=secret,
            secure=True
        )
        return True
    return False


def save_and_upload_file_asset(upload_file, upload_dir: str = "uploads", folder: str = "educonnect", media_type: str = "") -> UploadedMediaAsset:
    """Upload with provider metadata for lifecycle-managed chat attachments."""
    os.makedirs(upload_dir, exist_ok=True)
    clean_filename = os.path.basename(upload_file.filename or "file")
    is_video = _is_video(clean_filename, getattr(upload_file, "content_type", ""), media_type)
    unique_filename = f"{uuid.uuid4().hex}_{clean_filename}"
    local_path = os.path.join(upload_dir, unique_filename)
    upload_file.file.seek(0)
    with open(local_path, "wb+") as target:
        shutil.copyfileobj(upload_file.file, target)
    if configure_cloudinary():
        try:
            result = cloudinary.uploader.upload(local_path, **_cloudinary_upload_options(local_path, folder, is_video=is_video))
            if is_video:
                _queue_adaptive_streaming(result)
            url = result.get("secure_url") or result.get("url")
            if url:
                # The CDN upload is now durable.  Keeping a second full copy on
                # the API instance would eventually exhaust its ephemeral disk;
                # retain it only for the deliberate local-fallback path below.
                try:
                    os.remove(local_path)
                except OSError:
                    pass
                return UploadedMediaAsset(url, result.get("public_id"), _asset_resource_type(result, clean_filename, is_video))
        except Exception as exc:
            if _cloudinary_required():
                raise RuntimeError("Cloudinary is required but the media upload failed") from exc
            logger.warning("Cloudinary upload failed; using local fallback: %s", exc)
    elif _cloudinary_required():
        raise RuntimeError("Cloudinary is required but is not configured")
    return UploadedMediaAsset(f"/uploads/{unique_filename}")


def save_and_upload_path_asset(source_path: str, original_filename: str, upload_dir: str = "uploads", folder: str = "educonnect", media_type: str = "") -> UploadedMediaAsset:
    os.makedirs(upload_dir, exist_ok=True)
    clean_filename = os.path.basename(original_filename or "file")
    is_video = _is_video(clean_filename, media_type=media_type)
    unique_filename = f"{uuid.uuid4().hex}_{clean_filename}"
    local_path = os.path.join(upload_dir, unique_filename)
    shutil.copyfile(source_path, local_path)
    if configure_cloudinary():
        try:
            result = cloudinary.uploader.upload(local_path, **_cloudinary_upload_options(local_path, folder, is_video=is_video))
            if is_video:
                _queue_adaptive_streaming(result)
            url = result.get("secure_url") or result.get("url")
            if url:
                try:
                    os.remove(local_path)
                except OSError:
                    pass
                return UploadedMediaAsset(url, result.get("public_id"), _asset_resource_type(result, clean_filename, is_video))
        except Exception as exc:
            if _cloudinary_required():
                raise RuntimeError("Cloudinary is required but the media upload failed") from exc
            logger.warning("Cloudinary upload failed; using local fallback: %s", exc)
    elif _cloudinary_required():
        raise RuntimeError("Cloudinary is required but is not configured")
    return UploadedMediaAsset(f"/uploads/{unique_filename}")

# Initialize configuration on import
configure_cloudinary()

def save_and_upload_file(
    upload_file,
    upload_dir: str = "uploads",
    folder: str = "educonnect",
    media_type: str = "",
) -> str:
    """
    Saves an uploaded file safely.
    1. Saves a copy to upload_dir locally.
    2. If Cloudinary credentials are set, uploads to Cloudinary and returns the permanent HTTPS CDN URL.
    3. If Cloudinary is not configured or upload fails, gracefully falls back to the local '/uploads/{filename}'.
    """
    if not os.path.exists(upload_dir):
        os.makedirs(upload_dir, exist_ok=True)

    clean_filename = os.path.basename(upload_file.filename or "file")
    is_video = _is_video(clean_filename, getattr(upload_file, "content_type", ""), media_type)
    unique_filename = f"{uuid.uuid4().hex}_{clean_filename}"
    local_path = os.path.join(upload_dir, unique_filename)

    # Save locally first
    upload_file.file.seek(0)
    with open(local_path, "wb+") as f:
        shutil.copyfileobj(upload_file.file, f)

    # Attempt upload to Cloudinary if configured
    configured = configure_cloudinary()
    if configured:
        try:
            res = cloudinary.uploader.upload(
                local_path,
                **_cloudinary_upload_options(local_path, folder, is_video=is_video)
            )
            if is_video:
                _queue_adaptive_streaming(res)
            cloud_url = res.get("secure_url") or res.get("url")
            if cloud_url:
                print(f"[Cloudinary] Successfully uploaded {clean_filename} -> {cloud_url}")
                return cloud_url
        except Exception as e:
            if _cloudinary_required():
                raise RuntimeError("Cloudinary is required but the media upload failed") from e
            print(f"[Cloudinary] Upload failed for {clean_filename}, falling back to local: {e}")
    elif _cloudinary_required():
        raise RuntimeError("Cloudinary is required but is not configured")

    # Fallback to local URL
    return f"/uploads/{unique_filename}"


def save_and_upload_path(
    source_path: str,
    original_filename: str,
    upload_dir: str = "uploads",
    folder: str = "educonnect",
    media_type: str = "",
) -> str:
    """Store an already-assembled resumable upload using the normal media pipeline."""
    if not os.path.exists(upload_dir):
        os.makedirs(upload_dir, exist_ok=True)

    clean_filename = os.path.basename(original_filename or "file")
    is_video = _is_video(clean_filename, media_type=media_type)
    unique_filename = f"{uuid.uuid4().hex}_{clean_filename}"
    local_path = os.path.join(upload_dir, unique_filename)
    shutil.copyfile(source_path, local_path)

    configured = configure_cloudinary()
    if configured:
        try:
            res = cloudinary.uploader.upload(
                local_path,
                **_cloudinary_upload_options(local_path, folder, is_video=is_video)
            )
            if is_video:
                _queue_adaptive_streaming(res)
            cloud_url = res.get("secure_url") or res.get("url")
            if cloud_url:
                return cloud_url
        except Exception as e:
            if _cloudinary_required():
                raise RuntimeError("Cloudinary is required but the media upload failed") from e
            print(f"[Cloudinary] Upload failed for {clean_filename}, falling back to local: {e}")
    elif _cloudinary_required():
        raise RuntimeError("Cloudinary is required but is not configured")

    return f"/uploads/{unique_filename}"
