import sys
import os
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from app.core.llm import get_groq_llm
from langchain_core.messages import HumanMessage, SystemMessage

llm = get_groq_llm()

messages = [
    SystemMessage(content="You are JARVIS. Answer using the search evidence provided below. Do not use function call syntax."),
    HumanMessage(content="User Prompt: who is the current Chief Minister of Tamil Nadu do web search\n\nSearch Evidence:\nThe current Chief Minister of Tamil Nadu is M. K. Stalin (Muthuvel Karunanidhi Stalin), who assumed office on May 7, 2021.")
]

try:
    resp = llm.invoke(messages)
    print("SUCCESS:")
    print(resp.content)
except Exception as e:
    print("ERROR:")
    print(e)
