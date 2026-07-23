# 🤖 JARVIS OS — Grounded Agentic AI Assistant for Android

JARVIS OS is a production-grade, autonomous **Agentic AI System** for Android (Samsung Galaxy S21 FE / Android 10+). It features a **100% Search-Grounded LLM Engine**, **Zero Model Hallucinations**, **Qdrant Vector RAG Memory**, and an **Android Accessibility & MCP Tool Router** that allows the backend AI to directly control phone UI elements (clicking buttons, typing text, scrolling, launching apps, placing calls, sending SMS).

---

<p align="center">
  <img src="assets/banner.png" width="100%">
</p>

<p align="center">

![Android](https://img.shields.io/badge/Android-10%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple)
![Jetpack Compose](https://img.shields.io/badge/Compose-Material3-blue)
![FastAPI](https://img.shields.io/badge/FastAPI-Backend-009688)
![CrewAI](https://img.shields.io/badge/CrewAI-Agentic-red)
![License](https://img.shields.io/badge/License-MIT-blue)

</p>

---

## 📱 Preview

<p align="center">

![DEMO IMAGE 1](https://github.com/VarsshanCoder/JARVIS-OS-Agentic-AI-Assistant-for-Android/blob/main/preview/Screenshot%202026-07-23%20170903.png)
![DEMO IMAGE 2](https://github.com/VarsshanCoder/JARVIS-OS-Agentic-AI-Assistant-for-Android/blob/main/preview/Screenshot%202026-07-23%20170914.png)
![DEMO IMAGE 3](https://github.com/VarsshanCoder/JARVIS-OS-Agentic-AI-Assistant-for-Android/blob/main/preview/Screenshot%202026-07-23%20170941.png)

</p>

---

## 🏛️ System Architecture

```
                               ┌────────────────────────────────────────────────────────┐
                               │             Samsung Galaxy S21 FE (Android 14)          │
                               │   JarvisVoiceService (Foreground) + Accessibility      │
                               └───────────────────────────┬────────────────────────────┘
                                                           │ WebSocket Stream (JSON Frames)
                                                           ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                     JARVIS OS FASTAPI BACKEND SERVER                                    │
│                                                                                                         │
│  ┌───────────────────────────┐      ┌───────────────────────────┐      ┌─────────────────────────────┐  │
│  │ 1. Intent Classifier      │ ───► │ 2. Freshness Detector     │ ───► │ 3. Multi-Provider Search    │  │
│  │    (Intent & User State)  │      │    (Mandatory Tool Triggers│      │    (Google RSS 2026 /       │  │
│  └───────────────────────────┘      └───────────────────────────┘      │     Firecrawl / Wikipedia)  │  │
│                                                                        └──────────────┬──────────────┘  │
│                                                                                       │                 │
│  ┌───────────────────────────┐      ┌───────────────────────────┐                     ▼                 │
│  │ 6. Response Composer      │ ◄─── │ 5. Grounded LLM Engine    │ ◄─── ┌─────────────────────────────┐  │
│  │    Dual Mode [USER_REPLY] │      │    (Groq Llama-3.3-70b)   │      │ 4. Grounded Prompt Injector │  │
│  │    & [DEVELOPER_TRACE]    │      │    Strict Search Evidence │      │    Evidence Chunks Only     │  │
│  └─────────────┬─────────────┘      └───────────────────────────┘      └─────────────────────────────┘  │
└────────────────┼────────────────────────────────────────────────────────────────────────────────────────┘
                 │
                 │ Tool Requests & Stream Chunks
                 ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                 ANDROID KOTLIN ACCESSIBILITY EXECUTOR                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐   │
│  │  CLICK_TEXT  │  │  TYPE_TEXT   │  │    SCROLL    │  │  LAUNCH_APP  │  │ CALL_PHONE / SEND_SMS /   │   │
│  │ (Profile/UI) │  │(Input Fields)│  │(Gesture Swipe│  │(Insta/App)   │  │        SET_ALARM          │   │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘  └───────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## ⚡ Key Mechanisms

### 1. 100% Grounded Search & Zero-Hallucination Policy
- Model memory answers are **strictly prohibited** for any factual, news, election, sports, weather, stock, or temporal query (`today`, `latest`, `2025`, `2026`, `2027`, `winner`, `result`, `price`, `score`).
- Real-time search evidence is retrieved via **Google News RSS 2026**, **Firecrawl REST API**, and **Wikipedia API**, then injected directly into a grounded prompt context.
- If search evidence is unverified or insufficient, the assistant responds: *"I could not verify the latest information."*

### 2. Conversational Search Query Sanitizer
- Automatically strips conversational speech prefixes (`"do web search and say"`, `"do a web search for"`, `"search google for"`, `"tell me"`) before passing terms to search engines.
- Example: `"do web search and say who is the current CM of Kerala"` ➔ Sanitized query: `"who is the current CM of Kerala"`.

### 3. Dual-Mode Consumer Payload Engine
- Responses are generated in dual structured blocks:
  - **`[USER_REPLY]`**: Clean, natural, polished audio/chat bubble output for the end-user.
  - **`[DEVELOPER_TRACE]`**: Detailed reasoning, search evidence metrics, and latency logs for engineering analysis.

---

## 🛠️ Complete Tooling Matrix (Python ↔ Kotlin)

| Action Name | Python Tool Request (`tools.py`) | Kotlin Execution Handler (`JarvisCommandExecutor.kt`) | Phone Action Performed |
| :--- | :--- | :--- | :--- |
| **`LAUNCH_APP`** | `open_app`, `open_instagram`, etc. | `launchAppOrWeb()` | Launches target package (Instagram, WhatsApp, YouTube, Settings) |
| **`CALL_PHONE`** | `call_phone` | `Intent(Intent.ACTION_DIAL, "tel:...")` | Dials target contact phone number |
| **`SEND_SMS`** | `send_sms` | `Intent(Intent.ACTION_VIEW, "smsto:...")` | Opens SMS composer with target message |
| **`SET_ALARM`** | `set_alarm` | `Intent(AlarmClock.ACTION_SET_ALARM)` | Sets Android alarm clock for requested time |
| **`LAUNCH_MAPS`** | `launch_maps`, `navigate_to` | `JarvisCommandExecutor.openWebUrl()` | Opens Google Maps navigation |
| **`CLICK_TEXT`** | `click_text`, `switch_profile` | `JarvisAccessibilityService.clickText()` | Taps UI element by text (`"Profile"`, `"Settings"`, etc.) |
| **`TYPE_TEXT`** | `type_text` | `JarvisAccessibilityService.typeText()` | Inputs text into focused text field |
| **`SCROLL`** | `scroll` | `JarvisAccessibilityService.scroll()` | Performs accessibility scroll gesture (`"down"` / `"up"`) |
| **`WAIT_PACKAGE`** | `wait_for_package` | `JarvisAccessibilityService.waitForPackage()` | Verifies foreground app package |
| **`WAIT_TEXT`** | `wait_for_text` | `JarvisAccessibilityService.waitForText()` | Verifies UI element text presence |
| **`PRESS_BACK`** | `press_back` | `JarvisAccessibilityService.pressBack()` | Triggers global system Back button |
| **`PRESS_HOME`** | `press_home` | `JarvisAccessibilityService.pressHome()` | Triggers global system Home button |
| **`CAPTURE_TREE`** | `get_ui_tree` | `JarvisAccessibilityService.captureAccessibilityTree()` | Captures current UI node tree hierarchy |

---

## 🐞 Critical Bug Fixes Audit Log

1. **`NameError: name 'formatted_context' is not defined`**:
   - **Root Cause**: Scope omission in `agents.py` line 272 when building full LLM prompts.
   - **Fix**: Added explicit `formatted_context = ""` initialization block before RAG memory retrieval.

2. **Windows CP1252 `UnicodeEncodeError`**:
   - **Root Cause**: Non-ASCII characters (e.g. non-breaking hyphens `\u2011`) printed to Windows console.
   - **Fix**: Safe string encoding fallback added to backend diagnostic scripts (`streamed_text.encode('ascii', 'ignore').decode('ascii')`).

3. **Search Query Pollution**:
   - **Root Cause**: Conversational prefixes like `"do web search and say"` passed verbatim to search APIs, returning 0 hits.
   - **Fix**: Integrated regex query sanitizer `sanitize_search_query()`.

4. **Missing Kotlin Action Handlers**:
   - **Root Cause**: `SEND_SMS` Python tool request lacked explicit `smsto:` Intent binding in Kotlin.
   - **Fix**: Implemented `SEND_SMS` intent handler in `JarvisCommandExecutor.kt`.

---

## 🚀 How to Run Locally

### 1. Prerequisites
- Python 3.10+
- Android Studio Jellyfish / Ladybug
- Groq API Key (`GROQ_API_KEY`)
- Qdrant Vector Cloud Credentials (`QDRANT_HOST`, `QDRANT_API_KEY`)

### 2. Backend Setup
```bash
# Navigate to backend directory
cd backend

# Create virtual environment & install dependencies
python -m venv .venv
.venv\Scripts\activate  # Windows
# source .venv/bin/activate # Linux/Mac

pip install -r requirements.txt

# Create .env file
echo GROQ_API_KEY=your_groq_api_key > .env
echo QDRANT_HOST=https://your-qdrant-cluster.cloud.qdrant.io >> .env
echo QDRANT_API_KEY=your_qdrant_api_key >> .env

# Run local FastAPI server
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

---

## 🌐 How to Deploy Backend Online

### Option A: Docker Deployment (Recommended)

1. **Create `Dockerfile` in `backend/`**:
   ```dockerfile
   FROM python:3.11-slim
   WORKDIR /app
   COPY requirements.txt .
   RUN pip install --no-cache-dir -r requirements.txt
   COPY . .
   EXPOSE 8000
   CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
   ```

2. **Build and Run Docker Container**:
   ```bash
   docker build -t jarvis-backend .
   docker run -d -p 8000:8000 --env-file .env jarvis-backend
   ```

### Option B: Cloud Hosting (Render / Railway / DigitalOcean)

1. **Render.com**:
   - Connect your GitHub repository.
   - Select **Web Service** ➔ Build Command: `pip install -r requirements.txt` ➔ Start Command: `uvicorn app.main:app --host 0.0.0.0 --port 8000`.
   - Add Environment Variables: `GROQ_API_KEY`, `QDRANT_HOST`, `QDRANT_API_KEY`.

2. **Connecting Android Phone to Online Backend**:
   - Update `BASE_URL` in `android/core/network/src/main/java/com/jarvis/os/core/network/WebSocketClient.kt`:
     ```kotlin
     const val WS_URL = "wss://your-render-app.onrender.com/ws/assistant"
     ```
   - For local development tunneling to phone over Wi-Fi, use **Ngrok** or **Cloudflare Tunnel**:
     ```bash
     ngrok http 8000
     ```
