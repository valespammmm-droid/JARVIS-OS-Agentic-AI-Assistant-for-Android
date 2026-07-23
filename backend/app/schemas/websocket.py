from enum import Enum
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field

class WsEventType(str, Enum):
    CONNECT = "connect"
    CONNECTED = "connected"
    HEARTBEAT = "heartbeat"
    PING = "ping"
    PONG = "pong"
    USER_MESSAGE = "user_message"
    MESSAGE = "message"
    PLAN = "plan"
    TASK_START = "task_start"
    TASK_PROGRESS = "task_progress"
    TASK_COMPLETE = "task_complete"
    TOOL_REQUEST = "tool_request"
    TOOL_CALL = "tool_call"
    TOOL_RESULT = "tool_result"
    MEMORY_UPDATE = "memory_update"
    STREAM_START = "stream_start"
    STREAM_CHUNK = "stream_chunk"
    STREAM_END = "stream_end"
    ERROR = "error"
    CANCEL = "cancel"

class WsEnvelope(BaseModel):
    type: WsEventType
    payload: Dict[str, Any] = Field(default_factory=dict)
    context_state: Optional[Dict[str, Any]] = None

class PlanTaskStep(BaseModel):
    step_id: int
    agent_name: str
    tool_name: Optional[str] = None
    description: str

class PlanPayload(BaseModel):
    goal: str
    steps: List[PlanTaskStep] = Field(default_factory=list)

class ToolRequestPayload(BaseModel):
    request_id: str
    action: str
    package_name: Optional[str] = ""
    app_name: Optional[str] = ""
    target: Optional[str] = ""

class ToolResultPayload(BaseModel):
    request_id: str
    success: bool
    action: str
    data: Dict[str, Any] = Field(default_factory=dict)
    execution_time_ms: int = 0
    message: str = ""

class TaskProgressPayload(BaseModel):
    task_name: str
    status: str
    detail: str = ""
    agent: Optional[str] = ""
    tool: Optional[str] = ""
