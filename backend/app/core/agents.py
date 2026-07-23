import logging
import json
import time
import re
from typing import AsyncGenerator, Dict, Any, Optional, List, Tuple
from langchain_core.messages import HumanMessage, SystemMessage
from app.core.llm import get_groq_llm
from app.core.qdrant_memory import get_memory_service
from app.core.tools import ToolRegistry, FreshnessDetector, execute_firecrawl_search
from app.schemas.websocket import WsEventType

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """You are JARVIS, a 100% Grounded Autonomous AI Agent for Android.

GROUNDING & ZERO-HALLUCINATION RULES:
1. You are NOT allowed to answer factual, news, weather, election, stock, sports, live, or current questions from model memory.
2. If retrieved search evidence is provided below, answer ONLY using the retrieved search evidence.
3. Do NOT invent, extrapolate, or hallucinate information outside the retrieved evidence.
4. If retrieved search results are insufficient or unverified, state clearly: "I could not verify the latest information."

RESPONSE INSTRUCTIONS:
1. Provide your response structured cleanly into two sections:
   [USER_REPLY]
   (Write a concise, natural, polished answer directly addressing the user based ONLY on retrieved evidence. No robotic phrasing, no raw URL dumps.)
   [/USER_REPLY]
   [DEVELOPER_TRACE]
   (Internal reasoning, search evidence analysis, and trust metrics for developer mode.)
   [/DEVELOPER_TRACE]

2. ETIQUETTE:
   - Always sound natural, clear, and professional.
   - Do NOT append repetitive filler greetings like "At your service, boss." or "What can I help you with today?" to every response.
   - Never over-explain or show raw search snippets in [USER_REPLY]."""

class ResponseComposer:
    """
    Consumer Response Composer & Optimizer Engine.
    Strips internal developer logs, thought processes, and markdown artifacts from user outputs.
    Categorizes answer length and synthesizes clean, consumer-grade responses.
    """
    
    @staticmethod
    def is_small_question(user_text: str) -> bool:
        text = user_text.lower().strip()
        small_patterns = [
            "who is", "what is", "hi", "hello", "hey", "thanks", "time",
            "weather", "battery", "cm of", "pm of", "ceo of", "status"
        ]
        return len(text.split()) <= 10 or any(p in text for p in small_patterns)

    @staticmethod
    def parse_response(raw_text: str, user_text: str) -> Tuple[str, str]:
        user_reply = ""
        developer_trace = ""

        # Check for explicit [USER_REPLY] tags
        if "[USER_REPLY]" in raw_text and "[/USER_REPLY]" in raw_text:
            match = re.search(r'\[USER_REPLY\](.*?)\[/USER_REPLY\]', raw_text, re.DOTALL)
            if match:
                user_reply = match.group(1).strip()
            dev_match = re.search(r'\[DEVELOPER_TRACE\](.*?)\[/DEVELOPER_TRACE\]', raw_text, re.DOTALL)
            if dev_match:
                developer_trace = dev_match.group(1).strip()
        else:
            # Fallback regex parsing to strip **Thought:** and **Evidence:** blocks
            clean = raw_text
            # Extract Thought / Evidence for dev trace
            dev_parts = []
            thought_match = re.search(r'\*\*Thought:\*\*(.*?)(?=\*\*|\Z)', clean, re.DOTALL)
            if thought_match:
                dev_parts.append(f"Thought: {thought_match.group(1).strip()}")
            evidence_match = re.search(r'\*\*Evidence:\*\*(.*?)(?=\*\*Reply:\*\*|\Z)', clean, re.DOTALL)
            if evidence_match:
                dev_parts.append(f"Evidence: {evidence_match.group(1).strip()}")

            developer_trace = "\n".join(dev_parts)

            # Strip Thought & Evidence blocks from clean reply
            reply_match = re.search(r'\*\*Reply:\*\*(.*)', clean, re.DOTALL)
            if reply_match:
                user_reply = reply_match.group(1).strip()
            else:
                # Strip out any remaining **Thought:** or **Evidence:** headers
                user_reply = re.sub(r'\*\*Thought:\*\*.*?(?=\*\*Reply:\*\*|\Z)', '', clean, flags=re.DOTALL)
                user_reply = re.sub(r'\*\*Evidence:\*\*.*?(?=\*\*Reply:\*\*|\Z)', '', user_reply, flags=re.DOTALL)
                user_reply = re.sub(r'\*\*Reply:\*\*', '', user_reply).strip()

        # Clean up markdown junk, URLs, and repetitive trailing filler
        user_reply = re.sub(r'https?://\S+', '', user_reply).strip()
        user_reply = re.sub(r'\n{3,}', '\n\n', user_reply)
        user_reply = re.sub(r'\.?\s*(At your service, boss|What can I help you with today)\.?', '', user_reply, flags=re.IGNORECASE).strip()

        # Optimize small question answers to 1-3 direct sentences
        if ResponseComposer.is_small_question(user_text):
            sentences = [s.strip() for s in re.split(r'(?<=[.!?])\s+', user_reply) if s.strip()]
            if len(sentences) > 3:
                user_reply = " ".join(sentences[:3])

        if not user_reply:
            user_reply = "How can I help you?"

        return user_reply, developer_trace

def when_tool_executed_msg(action: str, target: str) -> str:
    act = action.upper()
    if act == "PRESS_BACK":
        return "Going back..."
    if act == "PRESS_HOME":
        return "Going home..."
    if act == "SCROLL":
        return f"Scrolling {target or 'down'}..."
    if act == "LAUNCH_APP":
        return f"Opening {target or 'app'}..."
    if act == "CALL_PHONE":
        return f"Calling {target}..."
    return f"Executing {action}..."

class AgentOrchestrator:
    """
    Production Consumer AI Agent Engine for JARVIS OS.
    Executes Firecrawl 2-Stage Search, Source Validation, ResponseComposer Engine,
    Dual Mode Payload Generation, Qdrant Vector Memory, and Real-Time Event Streaming.
    """
    def __init__(self):
        self.llm = get_groq_llm(temperature=0.7)
        self.memory = get_memory_service()
        if not self.llm:
            raise ValueError("Groq API key not configured. Please set GROQ_API_KEY in .env")

    async def process_user_request(
        self,
        user_text: str,
        context_state: Optional[Dict[str, Any]] = None
    ) -> AsyncGenerator[Dict[str, Any], None]:
        is_fresh_query = FreshnessDetector.requires_fresh_information(user_text)

        # Step 1: PLANNER AGENT - Micro Status Notification
        yield {
            "type": WsEventType.TASK_START.value,
            "payload": {
                "task_name": "PlannerAgent",
                "status": "planning",
                "detail": "Analyzing request...",
                "agent": "PlannerAgent"
            }
        }

        tool_req = ToolRegistry.create_tool_request(user_text)
        
        # Enforce Mandatory Tool calling for current / external / factual queries
        if is_fresh_query and not tool_req:
            tool_req = {
                "request_id": f"tool_{int(time.time())}",
                "tool": "web_search",
                "action": "WEB_SEARCH",
                "query": user_text
            }

        search_evidence = ""

        plan_steps = [
            {"step_id": 1, "agent_name": "FreshnessClassifier", "description": "Classify temporal requirements"}
        ]

        if tool_req:
            tool_id = tool_req.get("tool", "tool")
            plan_steps.append({
                "step_id": 2,
                "agent_name": "SearchAgent" if tool_id == "web_search" else "AndroidToolAgent",
                "tool_name": tool_id,
                "description": "Execute search" if tool_id == "web_search" else f"Execute {tool_id}"
            })
            plan_steps.append({
                "step_id": 3,
                "agent_name": "ResponseComposer",
                "description": "Compose polished consumer answer"
            })
        else:
            plan_steps.append({
                "step_id": 2,
                "agent_name": "ReasoningAgent",
                "description": "Synthesize response"
            })

        yield {
            "type": WsEventType.PLAN.value,
            "payload": {
                "goal": user_text,
                "steps": plan_steps
            }
        }

        # Step 2: SEARCH AGENT / TOOL EXECUTION
        if tool_req and tool_req.get("tool") == "web_search":
            query = tool_req.get("query", user_text)
            yield {
                "type": WsEventType.TASK_PROGRESS.value,
                "payload": {
                    "task_name": "SearchAgent",
                    "status": "searching_web",
                    "detail": "Searching web...",
                    "agent": "SearchAgent",
                    "tool": "web_search"
                }
            }

            search_result = execute_firecrawl_search(query)
            if search_result.get("success"):
                chunks = search_result.get("evidence_chunks", [])
                chunks_formatted = []
                for idx, c in enumerate(chunks, 1):
                    chunks_formatted.append(
                        f"[CHUNK #{idx}] Title: {c.get('title')} | URL: {c.get('url')}\n{c.get('chunk_text')}\n"
                    )
                
                evidence_block = "\n".join(chunks_formatted)
                search_evidence = f"\n\nExtracted Web Search Evidence:\n{evidence_block}\n"

                yield {
                    "type": WsEventType.TOOL_RESULT.value,
                    "payload": search_result
                }
        elif tool_req:
            action = tool_req.get("action", "").replace("_", " ").title()
            yield {
                "type": WsEventType.TASK_PROGRESS.value,
                "payload": {
                    "task_name": "AndroidToolAgent",
                    "status": "executing_tool",
                    "detail": f"{action}...",
                    "agent": "AndroidToolAgent",
                    "tool": tool_req.get("tool", "device_tool")
                }
            }
            yield {
                "type": WsEventType.TOOL_REQUEST.value,
                "payload": tool_req
            }

        # Step 3: RAG MEMORY AGENT (Personal memory context)
        memory_context = ""
        if not is_fresh_query:
            search_query = user_text
            if any(w in user_text.lower() for w in ["my name", "who am i", "call me", "remember me"]):
                search_query = "user name profile Varsh Varsshan"
            elif "father" in user_text.lower() or "mom" in user_text.lower() or "phone" in user_text.lower():
                search_query = f"{user_text} phone number contact"
            
            memories = self.memory.search_memories(search_query, limit=3)
            if memories:
                memory_context = "\nRelevant User Memory:\n" + "\n".join([f"- {m['text']}" for m in memories])

        formatted_context = ""
        if context_state:
            loc = context_state.get('location', {}).get('semanticLocation', 'UNKNOWN')
            bat = context_state.get('battery', {}).get('levelPercentage', 100)
            formatted_context = f"\nDevice State: Location={loc}, Battery={bat}%"

        # Resolve contact target if CALL_PHONE or SEND_SMS tool
        if tool_req and tool_req.get("action") in ["CALL_PHONE", "SEND_SMS"]:
            raw_target = tool_req.get("target", "")
            if not raw_target.isdigit() and len(raw_target) > 1:
                contact_memories = self.memory.search_memories(f"{raw_target} phone number", limit=2)
                for cm in contact_memories:
                    match = re.search(r'\b\d{10}\b', cm.get("text", ""))
                    if match:
                        tool_req["target"] = match.group(0)
                        break

        # Step 4: REASONING & RESPONSE COMPOSER AGENT
        yield {
            "type": WsEventType.TASK_PROGRESS.value,
            "payload": {
                "task_name": "ResponseComposer",
                "status": "generating",
                "detail": "Formulating response...",
                "agent": "ResponseComposer"
            }
        }

        # For pure device tools (press_back, press_home, scroll, launch_app), suppress repetitive LLM output
        if tool_req and tool_req.get("tool") in ["press_back", "press_home", "scroll", "launch_app", "call_phone"]:
            action = tool_req.get("action", "")
            target = tool_req.get("target", "") or tool_req.get("app_name", "")
            clean_reply = when_tool_executed_msg(action, target)
            dev_trace = f"Executed device action: {action}"
        else:
            full_prompt = f"User Request: {user_text}{formatted_context}{memory_context}{search_evidence}"
            messages = [
                SystemMessage(content=SYSTEM_PROMPT),
                HumanMessage(content=full_prompt)
            ]

            raw_accumulated = ""
            async for chunk in self.llm.astream(messages):
                if hasattr(chunk, 'content') and chunk.content:
                    raw_accumulated += chunk.content

            clean_reply, dev_trace = ResponseComposer.parse_response(raw_accumulated, user_text)

        yield {
            "type": WsEventType.STREAM_START.value,
            "payload": {"text": ""}
        }

        yield {
            "type": WsEventType.STREAM_CHUNK.value,
            "payload": {
                "text": clean_reply,
                "developer_trace": dev_trace,
                "done": False
            }
        }

        yield {
            "type": WsEventType.STREAM_END.value,
            "payload": {
                "text": clean_reply,
                "developer_trace": dev_trace,
                "done": True
            }
        }

        yield {
            "type": WsEventType.TASK_COMPLETE.value,
            "payload": {
                "task_name": "PlannerAgent",
                "status": "completed",
                "detail": "Response delivered.",
                "agent": "PlannerAgent"
            }
        }

        # Step 5: SILENT MEMORY UPDATE
        if clean_reply:
            self.memory.add_memory(
                text=f"User: {user_text} | JARVIS: {clean_reply}",
                category="conversation"
            )
            yield {
                "type": WsEventType.MEMORY_UPDATE.value,
                "payload": {"status": "saved", "category": "conversation"}
            }

_orchestrator: Optional[AgentOrchestrator] = None

def get_planner_agent() -> AgentOrchestrator:
    global _orchestrator
    if _orchestrator is None:
        _orchestrator = AgentOrchestrator()
    return _orchestrator
