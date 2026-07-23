import pytest
from app.core.llm import get_groq_llm
from app.core.config import settings

def test_groq_llm_initialization():
    llm = get_groq_llm()
    assert llm is not None
    assert llm.model_name == settings.GROQ_MODEL

@pytest.mark.skipif(
    not settings.GROQ_API_KEY,
    reason="Groq API key not configured"
)
def test_groq_llm_completion():
    llm = get_groq_llm()
    assert llm is not None
    try:
        response = llm.invoke("Hello, say 'Test successful'")
        print("Groq response:", response.content)
        assert len(response.content) > 0
    except Exception as e:
        # Skip if model doesn't exist or is not accessible
        if "model_not_found" in str(e) or "does not exist" in str(e):
            pytest.skip(f"Groq model not accessible: {e}")
        pytest.fail(f"Groq API invocation failed: {e}")
