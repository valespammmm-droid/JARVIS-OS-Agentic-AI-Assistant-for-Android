import json
import logging
from typing import List, Optional
from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Query
from app.core.agents import get_planner_agent
from app.schemas.websocket import WsEventType

logger = logging.getLogger(__name__)
router = APIRouter()

class ConnectionManager:
    def __init__(self):
        self.active_connections: List[WebSocket] = []

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)
        logger.info(f"WebSocket client connected. Active connections: {len(self.active_connections)}")

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)
        logger.info(f"WebSocket client disconnected. Active connections: {len(self.active_connections)}")

    async def send_json_message(self, message: dict, websocket: WebSocket):
        try:
            await websocket.send_text(json.dumps(message))
        except Exception as e:
            logger.error(f"Failed to send WS message: {e}")

manager = ConnectionManager()

@router.websocket("/ws")
async def websocket_endpoint(
    websocket: WebSocket,
    token: Optional[str] = Query(None)
):
    await manager.connect(websocket)
    
    # Handshake welcome event
    await manager.send_json_message({
        "type": WsEventType.CONNECTED.value,
        "payload": {
            "text": "JARVIS OS Autonomous Agent Platform connected. Ready for action, boss."
        }
    }, websocket)

    try:
        while True:
            data = await websocket.receive_text()
            try:
                message = json.loads(data)
                msg_type = message.get("type", "").lower()
                payload = message.get("payload", {})
                context_state = message.get("context_state")
                
                logger.info(f"Received WS message event: {msg_type}")
                
                if msg_type in (WsEventType.HEARTBEAT.value, WsEventType.PING.value, "heartbeat", "ping"):
                    await manager.send_json_message({
                        "type": WsEventType.PONG.value,
                        "payload": {"status": "ok"}
                    }, websocket)

                elif msg_type in (WsEventType.TOOL_RESULT.value, "tool_result"):
                    # Process tool execution result returned by Android device controller
                    req_id = payload.get("request_id", "")
                    success = payload.get("success", False)
                    result_msg = payload.get("message", "")
                    logger.info(f"Received TOOL_RESULT from Android: req_id={req_id}, success={success}, msg={result_msg}")

                elif msg_type in (WsEventType.USER_MESSAGE.value, WsEventType.MESSAGE.value, "message", "text"):
                    user_text = payload.get("text", "")
                    logger.info(f"Processing Agent request for prompt: {user_text}")
                    
                    orchestrator = get_planner_agent()
                    
                    try:
                        async for event in orchestrator.process_user_request(user_text, context_state):
                            await manager.send_json_message(event, websocket)
                            
                    except Exception as e:
                        logger.error(f"Error during agent streaming: {e}", exc_info=True)
                        await manager.send_json_message({
                            "type": WsEventType.ERROR.value,
                            "payload": {"message": f"Agent Execution Error: {str(e)}"}
                        }, websocket)
                        
            except json.JSONDecodeError:
                logger.error("Failed to parse incoming WebSocket JSON message")
                
    except WebSocketDisconnect:
        manager.disconnect(websocket)
