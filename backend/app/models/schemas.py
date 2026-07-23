from typing import Any, Dict, Optional, Union
from pydantic import BaseModel, Field

# Authentication
class UserCreate(BaseModel):
    email: str
    password: str
    full_name: Optional[str] = None

class UserResponse(BaseModel):
    id: int
    email: str
    full_name: Optional[str] = None
    is_active: bool

    class Config:
        from_attributes = True

class Token(BaseModel):
    access_token: str
    token_type: str

class TokenPayload(BaseModel):
    sub: Optional[str] = None

# WebSocket Communication
class WSMessage(BaseModel):
    type: str = Field(..., description="Message type: heartbeat, text, audio_chunk, command, response, error")
    payload: Dict[str, Any] = Field(default_factory=dict, description="Payload dictionary matching the message type")

class DeviceCommand(BaseModel):
    action: str = Field(..., description="Action to perform: open_app, make_call, send_sms, set_alarm, toggle_wifi, set_volume, etc.")
    params: Dict[str, Any] = Field(default_factory=dict, description="Parameters required for the action (e.g. app name, phone number, volume level)")
