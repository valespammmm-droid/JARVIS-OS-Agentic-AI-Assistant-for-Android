import asyncio
import os
from dotenv import load_dotenv

load_dotenv("c:/Users/LENOVO/Documents/crew jarvis/backend/.env")

async def fun_test():
    print("Testing Infrastructure Connections...")
    
    # 1. Groq LLM
    try:
        from langchain_groq import ChatGroq
        groq_key = os.getenv("GROQ_API_KEY")
        model_name = os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")
        llm = ChatGroq(groq_api_key=groq_key, model_name=model_name)
        res = await llm.ainvoke("Say 'JARVIS online'")
        print(f"[SUCCESS] Groq LLM connected! Response: {res.content}")
    except Exception as e:
        print(f"[FAIL] Groq LLM Error: {e}")

    # 2. Qdrant Vector DB
    try:
        from qdrant_client import QdrantClient
        qdrant_url = os.getenv("QDRANT_URL")
        qdrant_key = os.getenv("QDRANT_API_KEY")
        client = QdrantClient(url=qdrant_url, api_key=qdrant_key)
        collections = client.get_collections()
        print(f"[SUCCESS] Qdrant Cloud connected! Collections: {collections}")
    except Exception as e:
        print(f"[FAIL] Qdrant Error: {e}")

    # 3. Redis (Upstash)
    try:
        import redis
        redis_url = os.getenv("REDIS_URL")
        r = redis.from_url(redis_url)
        r.set("jarvis_test", "online")
        val = r.get("jarvis_test")
        print(f"[SUCCESS] Redis (Upstash) connected! Test key val: {val.decode()}")
    except Exception as e:
        print(f"[FAIL] Redis Error: {e}")

    # 4. PostgreSQL (Supabase)
    try:
        from sqlalchemy import create_engine, text
        db_url = os.getenv("DATABASE_URL")
        engine = create_engine(db_url)
        with engine.connect() as conn:
            result = conn.execute(text("SELECT 1"))
            print(f"[SUCCESS] PostgreSQL (Supabase) connected! Query result: {result.scalar()}")
    except Exception as e:
        print(f"[FAIL] PostgreSQL Error: {e}")

if __name__ == "__main__":
    asyncio.run(fun_test())
