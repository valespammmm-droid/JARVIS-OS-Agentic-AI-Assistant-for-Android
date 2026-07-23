import asyncio
import websockets
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

async def test_consumer_response():
    uri = "ws://localhost:8000/api/v1/ws"
    print(f"Connecting to {uri}...")
    
    async with websockets.connect(uri) as ws:
        welcome = await ws.recv()
        print(f"Server Welcome: {welcome}")

        prompt = {
            "type": "message",
            "payload": {"text": "who is the current Chief Minister of Tamilnadu"}
        }
        print(f"\nSending prompt: {prompt['payload']['text']}")
        await ws.send(json.dumps(prompt))

        print("\nStreaming response from backend agent:")
        while True:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=12.0)
                data = json.loads(msg)
                msg_type = data.get("type")
                payload = data.get("payload", {})
                
                if msg_type == "task_progress":
                    print(f"[PROGRESS] ({payload.get('agent')}): {payload.get('detail')}")
                elif msg_type == "stream_chunk" or msg_type == "stream_end":
                    clean_text = payload.get("text", "")
                    dev_trace = payload.get("developer_trace", "")
                    print(f"\n--- NORMAL MODE USER RESPONSE ---\n{clean_text}")
                    if dev_trace:
                        print(f"\n--- DEVELOPER MODE TRACE ---\n{dev_trace}")
                elif msg_type == "task_complete":
                    print("\n[Task Complete Successfully]")
                    break
            except asyncio.TimeoutError:
                print("\n[Timeout]")
                break

if __name__ == "__main__":
    asyncio.run(test_consumer_response())
