from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.core.config import settings
from app.routers import health, websocket
from app.core.database import Base, engine
import logging

# Configure Logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# Create Database tables on startup (Phase 1, simple initialization)
try:
    Base.metadata.create_all(bind=engine)
    logger.info("Database tables initialized successfully.")
except Exception as e:
    logger.error(f"Error initializing database tables: {e}")

app = FastAPI(
    title=settings.APP_NAME,
    description="Backend service orchestrating the multi-agent system and audio streaming for JARVIS OS",
    version="1.0.0",
    openapi_url=f"{settings.API_V1_STR}/openapi.json"
)

# Set up CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # In production, specify the Android app's package/domain origin
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Register routers
app.include_router(health.router, prefix=settings.API_V1_STR, tags=["health"])
app.include_router(websocket.router, prefix=settings.API_V1_STR, tags=["websocket"])

@app.get("/")
def read_root():
    return {
        "status": "online",
        "message": "Welcome to JARVIS OS Personal Assistant Backend",
        "version": "1.0.0"
    }

