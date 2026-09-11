import os

from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import NullPool


def _positive_int_env(name: str, default: int, maximum: int) -> int:
    """Read deployment tuning safely; a bad environment value must not stop boot."""
    try:
        return max(1, min(int(os.environ.get(name, default)), maximum))
    except (TypeError, ValueError):
        return default


DATABASE_URL = os.environ.get("DATABASE_URL", "sqlite:///./sql_app_final.db")

# Render / Heroku Postgres connection string compatibility
if DATABASE_URL.startswith("postgres://"):
    DATABASE_URL = DATABASE_URL.replace("postgres://", "postgresql://", 1)

if "sqlite" in DATABASE_URL:
    engine = create_engine(
        DATABASE_URL,
        connect_args={"check_same_thread": False},
        pool_pre_ping=True,
    )
else:
    # Each web replica has its own SQLAlchemy pool. Keep this deliberately
    # small, then use PgBouncer when replicas grow; otherwise N replicas can
    # silently exhaust Neon/Postgres connections.
    db_pool_mode = os.environ.get("DB_POOL_MODE", "direct").strip().lower()
    connect_args = {"connect_timeout": _positive_int_env("DB_CONNECT_TIMEOUT_SECONDS", 10, 60)}
    if db_pool_mode == "pgbouncer":
        # PgBouncer owns connection pooling. Do not keep an additional pool
        # inside every Gunicorn worker, which would defeat its purpose.
        engine = create_engine(
            DATABASE_URL,
            poolclass=NullPool,
            pool_pre_ping=True,
            connect_args=connect_args,
        )
    else:
        engine = create_engine(
            DATABASE_URL,
            pool_pre_ping=True,
            pool_recycle=_positive_int_env("DB_POOL_RECYCLE_SECONDS", 300, 3600),
            pool_size=_positive_int_env("DB_POOL_SIZE", 5, 50),
            max_overflow=_positive_int_env("DB_MAX_OVERFLOW", 5, 100),
            pool_timeout=_positive_int_env("DB_POOL_TIMEOUT_SECONDS", 30, 120),
            connect_args=connect_args,
        )

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
