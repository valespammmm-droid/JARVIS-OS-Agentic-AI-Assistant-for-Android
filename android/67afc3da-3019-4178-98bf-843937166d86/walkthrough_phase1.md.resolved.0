# JARVIS OS - Phase 1 Foundation Walkthrough

This document guides you through running, testing, and verifying the Phase 1 Foundation of **JARVIS OS**.

## 1. Backend Service

The backend is built using **FastAPI** and is fully modularized with database connection managers, routers, configuration parsing, and test suites.

### Project Structure
- `app/main.py`: Entrypoint registering routers and database initialization.
- `app/core/config.py`: Configuration variables loaded from `.env` with fallbacks.
- `app/core/database.py`: SQLAlchemy session generation with test client support.
- `app/core/security.py`: JWT utilities and password hashing.
- `app/models/schemas.py`: Data contracts for authorization and WebSockets.
- `app/routers/health.py`: Endpoint checking database, Redis, and Qdrant readiness.
- `app/routers/websocket.py`: Connection manager implementing message frame parsing (text streaming chunks).
- `tests/test_main.py`: Pytest suite verifying correctness.

### How to Run Locally
1. Navigate to the `backend/` directory:
   ```powershell
   cd backend
   ```
2. Activate the virtual environment:
   ```powershell
   .venv\Scripts\activate
   ```
3. Run the FastAPI development server:
   ```powershell
   uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
   ```
   *(Access the interactive OpenAPI swagger docs at [http://127.0.0.1:8000/docs](http://127.0.0.1:8000/docs))*

### How to Run Tests
From the `backend/` directory, run:
```powershell
.venv\Scripts\pytest.exe tests/
```
The test suite validates:
- The base greeting endpoint.
- The system connectivity health check router.

---

## 2. Android Module

The Android project is initialized with a **multi-module Clean Architecture** design that separates core utilities (Network, Databases, Speech, Background scheduling, UI theme) from user features (Overlay Assistant Drawer, Device Skills execution).

### Module Definitions
- `:app`: Launching application module containing `MainActivity` (Compose permission dashboard).
- `:core:network`: Ktor HTTP & WebSockets streaming client configurations.
- `:core:database`: Room Local Cache database schema logic.
- `:core:speech`: Audio recording listener APIs, local wakeword configurations, and TTS.
- `:core:background`: Foreground audio listener execution and WorkManager scheduling.
- `:core:ui`: Materials 3 base configurations, obsidian typography fonts, gradients, and custom glassmorphism borders.
- `:feature:assistant`: Bottom Overlay Dialog sheet layout and micro-animations.
- `:feature:skills`: Intent mapping representing actions (SMS, alarms, volume adjustment).

### How to Build
To inspect and open the Android structure:
1. Open the `/android` folder in **Android Studio**.
2. Run a Gradle synchronization (`Sync Project with Gradle Files`).
3. Deploy the `:app` configuration on an Android emulator or device targeting API Level 34/35+.
