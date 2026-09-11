# Horizontal scaling runbook

This backend is safe to run on one instance with its default settings.  Do not
increase replica count until the deployment uses the settings below.

## Required production environment

Set `DATABASE_URL` to PostgreSQL/Neon, never the local SQLite fallback.
Set `REQUIRE_CLOUDINARY=true` and valid Cloudinary credentials.  This prevents
final media URLs from being written to an individual web instance's disk.

For multiple web replicas, point `DATABASE_URL` at a PgBouncer endpoint and set
`DB_POOL_MODE=pgbouncer`.  In that mode SQLAlchemy uses `NullPool`; PgBouncer is
the sole owner of connections.  Do not use a direct Neon URL with many replicas
and large `DB_POOL_SIZE` values.

Set `WEB_CONCURRENCY` conservatively per instance (normally `2` for a small
Render instance).  Increase replica count first, then tune workers only after
observing CPU, latency, and database wait metrics.

Use `/healthz` for liveness and `/readyz` for readiness.  A load balancer must
only route traffic to replicas that return HTTP 200 from `/readyz`.

## Load shedding and retry behavior

The app has an in-process sliding-window limiter: default API traffic is 120
requests/minute/IP, authentication is 20/minute/IP, and resumable upload chunks
are 180/minute/IP.  Tune these only through `RATE_LIMIT_API_PER_MINUTE`,
`RATE_LIMIT_AUTH_PER_MINUTE`, and `RATE_LIMIT_UPLOAD_CHUNKS_PER_MINUTE`.

This protects one process.  With multiple replicas, enforce the same limits at
the load balancer/API gateway or through Redis; in-memory counters cannot be
shared by independent processes.  Set `TRUST_PROXY_HEADERS=true` only when the
platform overwrites `X-Forwarded-For` with the real client address.

The current synchronous SQLAlchemy endpoints are intentionally left synchronous:
turning them into `async def` without an async database driver would block the
event loop.  A future async migration must move the database session, queries,
and any blocking provider calls together, with endpoint-by-endpoint load tests.

## Resumable upload constraint

Current resumable chunks are assembled at `RESUMABLE_UPLOAD_DIR`.  A local path
is correct for one replica only.  Before more than one replica is enabled,
replace it with a shared durable upload implementation (object storage with a
shared upload ID/offset, or Cloudinary's direct resumable upload flow).  Merely
setting a path on independent Render disks is not shared storage.

Until that migration is deployed, configure load-balancer session affinity for
the resumable-upload endpoints or retain a single API replica for them.  Do not
claim cross-replica resume support otherwise.

## Deployment command

`Procfile` runs Gunicorn with Uvicorn workers for production.  Local development
continues to use `run_server.bat` and Uvicorn reload; never use reload in a
production replica.
