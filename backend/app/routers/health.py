from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session
from sqlalchemy import text
from app.core.database import get_db
from app.core.config import settings
import redis
from qdrant_client import QdrantClient
import logging

logger = logging.getLogger(__name__)
router = APIRouter()

@router.get("/health")
def health_check(db: Session = Depends(get_db)):
    status = {
        "status": "healthy",
        "database": "unhealthy",
        "redis": "unhealthy",
        "qdrant": "unhealthy"
    }
    
    # 1. Check PostgreSQL
    try:
        db.execute(text("SELECT 1"))
        status["database"] = "healthy"
    except Exception as e:
        logger.error(f"Database health check failed: {e}")
        status["status"] = "unhealthy"

    # 2. Check Redis
    try:
        r = redis.Redis.from_url(settings.REDIS_URL, socket_timeout=2)
        if r.ping():
            status["redis"] = "healthy"
    except Exception as e:
        logger.error(f"Redis health check failed: {e}")
        status["status"] = "unhealthy"

    # 3. Check Qdrant
    try:
        q = QdrantClient(url=settings.QDRANT_URL, api_key=settings.QDRANT_API_KEY, timeout=2)
        q.get_collections()
        status["qdrant"] = "healthy"
    except Exception as e:
        logger.error(f"Qdrant health check failed: {e}")
        # Note: We won't mark the whole service unhealthy if only vector db is down,
        # but we report it. Or we can mark it unhealthy depending on criticality.
        status["qdrant"] = f"unhealthy: {str(e)}"
        status["status"] = "unhealthy"

    return status
