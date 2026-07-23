import asyncio
import websockets
import json
import sys

# Force UTF-8 stdout
sys.stdout.reconfigure(encoding='utf-8')

async def test_full_pipeline():
    uri = "ws://localhost:8000/api/v1/ws"
    print(f"Connecting to {uri}...")
    
    async with websockets.connect(uri) as ws:
        # Read welcome message
        welcome = await ws.recv()
        print(f"Server Welcome: {welcome}")

        # Send heartbeat
        await ws.send(json.dumps({"type": "heartbeat"}))
        hb_res = await ws.recv()
        print(f"Heartbeat Response: {hb_res}")

        # Send test prompt with ContextState snapshot
        prompt = {
            "type": "text",
            "payload": {"text": "Jarvis, open Instagram and set alarm for 7 AM."},
            "context_state": {
                "location": {"semanticLocation": "COLLEGE"},
                "motion": {"type": "WALKING"},
                "battery": {"levelPercentage": 85},
                "audio": {"isHeadphonesConnected": True}
            }
        }
        print(f"\nSending prompt: {prompt['payload']['text']}")
        await ws.send(json.dumps(prompt))

        print("\nStreaming response from backend agent:")
        while True:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=6.0)
                data = json.loads(msg)
                msg_type = data.get("type")
                text_chunk = data.get("payload", {}).get("text", "")
                intent = data.get("payload", {}).get("intent")
                
                if intent:
                    print(f"\n[INTENT DETECTED]: {intent}")
                if text_chunk:
                    print(text_chunk, end="", flush=True)
            except asyncio.TimeoutError:
                print("\n\n[Stream completed successfully]")
                break

if __name__ == "__main__":
    asyncio.run(test_full_pipeline())
