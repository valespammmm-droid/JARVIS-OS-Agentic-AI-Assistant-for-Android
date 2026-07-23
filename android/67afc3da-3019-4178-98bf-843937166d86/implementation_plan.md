# JARVIS OS - Implementation Plan

This implementation plan outlines the engineering and design architecture for **JARVIS OS**, a production-quality Personal AI Operating System. 

## Architectural Overview

JARVIS OS is designed around a decoupled client-server architecture with real-time bidirectional communication over WebSockets.

```mermaid
graph TD
    subgraph Android Device (Kotlin & Jetpack Compose)
        A[Foreground Audio Service] -->|Offline wake word: PocketSphinx/Custom VAD| B(Vibration / Sound Feedback)
        A -->|Wake Event| C[Bottom Assistant Overlay UI]
        C -->|Audio Streaming / SpeechRecognizer| D[Network Module - WebSocket]
        C -->|Text Input| D
        D -->|Device Control Commands| E[Device Tools Handler]
        E -->|Executes Wi-Fi, Alarms, SMS, Bluetooth, etc.| F[Android Framework APIs]
    end

    subgraph Backend Server (FastAPI, CrewAI, & Databases)
        D <-->|Bidirectional WebSockets / JSON| G[FastAPI Gateway]
        G -->|Audio Stream| H[STT Whisper Engine]
        G -->|Text Prompt| I[Planner Agent / CrewAI Orchestrator]
        I -->|RAG Retrieval| J[VectorDB Qdrant]
        I -->|State & Context| K[Redis Cache / PostgreSQL]
        I -->|Tools Planning| L[Device Command Generator]
        I -->|LLM Inference| M[DeepSeek V4 Flash / GLM-5.2]
        L -->|Return Tool Execution JSON| G
        I -->|Generate Reply Text| N[TTS ElevenLabs/Streaming engine]
        N -->|Audio Stream / Speech Output| G
    end
```

---

## Phase 1: Project Skeleton, Foundation & API Gateway

### Goals
1. Initialize the modular project layout for both Android and Backend.
2. Build the FastAPI core server with base routers (health, sockets, config).
3. Set up the Android workspace with Gradle configs, clean architecture directory structures, and dependencies (Hilt, Jetpack Compose, Room, WorkManager, Coroutines).
4. Establish the shared JSON and WebSocket message contracts.

### Folder Structure
```
crew jarvis/
├── backend/
│   ├── app/
│   │   ├── __init__.py
│   │   ├── main.py
│   │   ├── core/
│   │   │   ├── config.py
│   │   │   ├── security.py
│   │   │   └── database.py
│   │   ├── routers/
│   │   │   ├── __init__.py
│   │   │   ├── health.py
│   │   │   └── websocket.py
│   │   ├── services/
│   │   └── models/
│   ├── tests/
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── requirements.txt
├── android/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── app/
│   ├── core/
│   │   ├── network/
│   │   ├── database/
│   │   ├── speech/
│   │   ├── background/
│   │   └── ui/
│   └── feature/
│       ├── assistant/
│       └── skills/
└── artifacts/
```

---

## Phase 2: Offline Wake Word Engine & Android Background System

### Goals
1. Implement a Foreground Service that manages audio recording and keeps the assistant alive in the background.
2. Build an offline wake-word detector using a lightweight library (such as Picovoice Porcupine or a Custom VAD + TFLite/ONNX engine for "Jarvis").
3. Wire up the BootReceiver to restart the service on device reboot, complying with Android 15 restrictions.
4. Set up foreground notifications, battery optimization request prompts, and test the wake word in background states.

---

## Phase 3: Bottom Assistant Overlay UI & Voice/Text Input Stream

### Goals
1. Build a premium Google Assistant-style bottom overlay UI in Jetpack Compose, utilizing dark theme, glassmorphism, soft gradients, and custom dynamic blur.
2. Configure System Alert Window / Overlay permissions (`SYSTEM_ALERT_WINDOW`) to show the sheet above all apps on wake.
3. Add a dynamic voice waveform animation, scrolling text input, and gesture sheet support.
4. Create the streaming STT service (sending audio chunks over WebSocket to the Whisper service or utilizing local Android SpeechRecognizer).

---

## Phase 4: FastAPI Backend with CrewAI Agentic Architecture & Memory RAG

### Goals
1. Build the multi-agent brain using CrewAI:
   - **Planner Agent**: Interprets commands, coordinates sub-agents, selects LLM (DeepSeek vs GLM).
   - **Memory Agent**: Coordinates RAG retrieval from Qdrant/PostgreSQL.
   - **Device Agent**: Formulates JSON payload instructions for Android device execution.
2. Connect Redis for caching and session context, PostgreSQL for user data and logs, Qdrant for RAG-based long-term memory.
3. Wire the WebSocket handler to stream text tokens and process commands in real time.

---

## Phase 5: Android Device Control Integration, Speech TTS & Final E2E Polish

### Goals
1. Implement local skills handlers in Android to execute plans (Wi-Fi, Bluetooth, alarms, SMS, system settings).
2. Set up natural voice replies via streaming TTS (ElevenLabs, Coqui, or Android local TTS).
3. Test end-to-end latency targets: wake-word <150ms, speech recognition starts <500ms, first token <700ms.
4. Final lint check, performance optimization, and project packaging.
