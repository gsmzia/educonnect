# Production security configuration

Set these environment variables in the deployment dashboard; do not place real
values in source control.

- `ENVIRONMENT=production`
- `AUTH_SESSION_SECRET=<at least 32 random bytes, encoded as a long random string>`
- `AUTH_SESSION_TTL_SECONDS=2592000` (optional; 30 days by default)

Generate a suitable secret locally with:

```powershell
python -c "import secrets; print(secrets.token_urlsafe(48))"
```

When `ENVIRONMENT` is production and `AUTH_SESSION_SECRET` is absent, protected
academic routes fail closed rather than accepting forgeable sessions. Changing
the secret intentionally signs all users out.

## Media lifecycle scheduler

Set `MEDIA_PURGE_SECRET` to a separate random value (also at least 32 random
bytes). Configure one daily Render Cron Job, not one job per web replica, to
call:

```text
curl --fail --silent --show-error -X POST \
  -H "X-Media-Purge-Secret: $MEDIA_PURGE_SECRET" \
  https://<your-api-host>/admin/purge-expired-group-media
```

Pass the secret as a header, never as a query parameter, so it is not exposed
in proxy URL logs. The endpoint is intentionally secret-protected and idempotent. It retires only
known, unshared Cloudinary assets: group attachments at least seven days old and
direct attachments whose receiver has already confirmed a durable local save.
It never deletes local device files, shared/forwarded assets, or another user's
chat history.
