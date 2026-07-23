"""Tests for the PlannerAgent."""
import os
import sys
import pytest
from unittest.mock import patch, MagicMock

# Add parent directory to path so python can find app/
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.agents import PlannerAgent, get_planner_agent, SYSTEM_MESSAGE


def test_planner_agent_system_message():
    """Test that the PlannerAgent has the correct system message."""
    assert SYSTEM_MESSAGE == "You are JARVIS, a helpful personal AI assistant. Respond concisely and conversationally."


def test_planner_agent_instantiation():
    """Test that PlannerAgent can be instantiated."""
    agent = PlannerAgent()
    assert agent is not None
    assert hasattr(agent, 'llm')
    assert hasattr(agent, 'respond')


def test_planner_agent_get_instance():
    """Test that get_planner_agent returns a PlannerAgent instance."""
    with patch('app.core.agents._planner_agent', None):
        with patch('app.core.agents.get_groq_llm', return_value=MagicMock()):
            agent = get_planner_agent()
            assert isinstance(agent, PlannerAgent)
            assert agent is not None


def test_planner_agent_without_api_key():
    """Test that PlannerAgent raises error when Groq API key is not configured."""
    with patch('app.core.agents.get_groq_llm', return_value=None):
        with pytest.raises(ValueError, match="Groq API key not configured"):
            PlannerAgent()


def test_planner_agent_respond_method_exists():
    """Test that PlannerAgent has the respond method."""
    agent = PlannerAgent()
    assert hasattr(agent, 'respond')
    assert callable(agent.respond)


def test_planner_agent_respond_async():
    """Test that respond is an async generator method."""
    import inspect
    agent = PlannerAgent()
    # respond is an async generator function
    assert inspect.isasyncgenfunction(agent.respond)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
