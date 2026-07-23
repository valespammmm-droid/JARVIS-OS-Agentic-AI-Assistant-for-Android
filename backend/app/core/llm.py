import os
from typing import Optional
from langchain_groq import ChatGroq
from app.core.config import settings

def get_groq_llm(temperature: float = 0.7) -> Optional[ChatGroq]:
    """
    Returns an instance of ChatGroq using the configured GROQ_API_KEY.
    """
    if not settings.GROQ_API_KEY:
        return None
    
    return ChatGroq(
        model=settings.GROQ_MODEL,
        groq_api_key=settings.GROQ_API_KEY,
        temperature=temperature,
        model_kwargs={"tool_choice": "none"}
    )
