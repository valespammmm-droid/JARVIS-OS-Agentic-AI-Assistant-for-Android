import os
import sys
import pytest
from fastapi.testclient import TestClient

# Add parent directory to path so python can find app/
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Set SQLite database URL for testing before importing app
os.environ["DATABASE_URL"] = "sqlite:///./test.db"

from app.main import app

client = TestClient(app)


def test_read_root():
    response = client.get("/")
    assert response.status_code == 200
    assert response.json()["status"] == "online"


def test_health_check():
    response = client.get("/api/v1/health")
    # Health check might return status: unhealthy if redis/qdrant are not running locally,
    # but the API endpoint itself should respond with a 200 OK.
    assert response.status_code == 200
    assert "status" in response.json()


@pytest.mark.skip(reason="WebSocket streaming test requires live Groq API key")
def test_websocket_text_streaming():
    """Test that WebSocket endpoint handles text streaming with Groq LLM.
    
    This test is skipped by default as it requires a valid GROQ_API_KEY.
    To run with a live API key, set GROQ_API_KEY in .env and remove the skip decorator.
    """
    with TestClient(app) as test_client:
        with test_client.websocket_connect("/ws?token=test_token") as websocket:
            # Send a text message
            websocket.send_json({
                "type": "text",
                "payload": {"text": "Hello, JARVIS!"}
            })
            
            # Receive the welcome message
            welcome = websocket.receive_json()
            assert welcome["type"] == "response"
            assert "Hello" in welcome["payload"]["text"]
            
            # Receive streaming chunks
            chunks = []
            done_received = False
            
            while not done_received:
                try:
                    chunk = websocket.receive_json()
                    if chunk["type"] == "response_chunk":
                        chunks.append(chunk)
                        if chunk["payload"]["done"]:
                            done_received = True
                    elif chunk["type"] == "error":
                        pytest.fail(f"Received error: {chunk['payload']['message']}")
                except Exception:
                    # WebSocket may timeout or close
                    break
            
            # Verify we received at least some chunks
            assert len(chunks) > 0
            
            # Verify the last chunk has done=true
            last_chunk = chunks[-1]
            assert last_chunk["payload"]["done"] is True
