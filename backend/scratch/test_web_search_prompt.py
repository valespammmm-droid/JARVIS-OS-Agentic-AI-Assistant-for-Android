import asyncio
import websockets
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

async def test_web_search_prompt():
    uri = "ws://localhost:8000/api/v1/ws"
    print(f"Connecting to {uri}...")
    
    async with websockets.connect(uri) as ws:
        welcome = await ws.recv()
        print(f"Server Welcome: {welcome}")

        prompt = {
            "type": "message",
            "payload": {"text": "who is the current Chief Minister of Tamil Nadu"}
        }
        print(f"\nSending prompt: {prompt['payload']['text']}")
        await ws.send(json.dumps(prompt))

        print("\nStreaming response from backend agent:")
        while True:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=10.0)
                data = json.loads(msg)
                msg_type = data.get("type")
                payload = data.get("payload", {})
                
                if msg_type == "task_progress":
                    print(f"\n[TASK PROGRESS] ({payload.get('agent')}): {payload.get('detail')}")
                elif msg_type == "tool_result":
                    print(f"\n[TOOL RESULT]: Success={payload.get('success')}, Tool={payload.get('tool')}")
                elif msg_type == "stream_chunk":
                    print(payload.get("text", ""), end="", flush=True)
                elif msg_type == "error":
                    print(f"\n[ERROR]: {payload.get('message')}")
                    break
                elif msg_type == "task_complete":
                    print("\n\n[Task Complete Successfully]")
                    break
            except asyncio.TimeoutError:
                print("\n\n[Timeout - Stream Finished]")
                break

if __name__ == "__main__":
    asyncio.run(test_web_search_prompt())
