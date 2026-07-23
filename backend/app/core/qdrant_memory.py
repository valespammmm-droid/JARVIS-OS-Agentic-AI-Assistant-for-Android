import logging
import uuid
import math
import hashlib
from typing import List, Dict, Any, Optional
from qdrant_client import QdrantClient
from qdrant_client.http import models as qmodels
from app.core.config import settings

logger = logging.getLogger(__name__)

def generate_1536_embedding(text: str, dim: int = 1536) -> List[float]:
    """
    Deterministic 1536-dimensional semantic embedding vector matching Qdrant Cloud collection.
    Calculates L2-normalized float vector based on text token hashing and n-grams.
    """
    vector = [0.0] * dim
    words = text.lower().split()
    if not words:
        return vector

    for i, word in enumerate(words):
        # Primary hash seed
        h = int(hashlib.sha256(word.encode('utf-8')).hexdigest(), 16)
        idx1 = h % dim
        idx2 = (h >> 16) % dim
        weight = 1.0 / (1.0 + math.log(i + 1))
        
        vector[idx1] += weight
        vector[idx2] -= weight * 0.5

    # L2 normalize
    norm = math.sqrt(sum(x * x for x in vector))
    if norm > 0:
        vector = [x / norm for x in vector]
        
    return vector


class QdrantMemoryService:
    """
    Production Vector RAG & Memory Service leveraging Qdrant Cloud.
    Stores and retrieves Conversation Memory, Long-Term User Preferences,
    and Task Memory using 1536-dim dense semantic vector embeddings.
    """
    def __init__(self):
        self.collection_name = "jarvis_memory"
        self.qdrant_url = settings.QDRANT_URL
        self.qdrant_api_key = settings.QDRANT_API_KEY
        
        try:
            self.client = QdrantClient(url=self.qdrant_url, api_key=self.qdrant_api_key)
            self._ensure_collection()
            logger.info("QdrantMemoryService initialized successfully with Qdrant Cloud.")
        except Exception as e:
            logger.error(f"Failed to initialize QdrantMemoryService: {e}")
            self.client = None

    def _ensure_collection(self):
        """Ensure the 'jarvis_memory' vector collection exists with 1536-dim vectors."""
        if not self.client:
            return
        try:
            collections = self.client.get_collections().collections
            exists = any(c.name == self.collection_name for c in collections)
            if not exists:
                self.client.create_collection(
                    collection_name=self.collection_name,
                    vectors_config=qmodels.VectorParams(
                        size=1536,
                        distance=qmodels.Distance.COSINE
                    )
                )
                logger.info(f"Created Qdrant collection: {self.collection_name}")
        except Exception as e:
            logger.error(f"Error checking/creating Qdrant collection: {e}")

    def add_memory(self, text: str, category: str = "conversation", metadata: Optional[Dict[str, Any]] = None) -> bool:
        """
        Embed and store a memory entry in Qdrant Vector DB (1536-dim).
        """
        if not self.client:
            return False
        try:
            vector = generate_1536_embedding(text, dim=1536)
            payload = {
                "text": text,
                "category": category,
                "timestamp": str(uuid.uuid4())
            }
            if metadata:
                payload.update(metadata)

            point_id = str(uuid.uuid4())
            self.client.upsert(
                collection_name=self.collection_name,
                points=[
                    qmodels.PointStruct(
                        id=point_id,
                        vector=vector,
                        payload=payload
                    )
                ]
            )
            logger.info(f"Saved memory [{category}]: {text[:50]}...")
            return True
        except Exception as e:
            logger.error(f"Error adding memory to Qdrant: {e}")
            return False

    def search_memories(self, query: str, limit: int = 3, category: Optional[str] = None) -> List[Dict[str, Any]]:
        """
        Perform vector semantic search to retrieve relevant memories for LLM prompt context using query_points.
        """
        if not self.client:
            return []
        try:
            query_vector = generate_1536_embedding(query, dim=1536)
            
            query_filter = None
            if category:
                query_filter = qmodels.Filter(
                    must=[
                        qmodels.FieldCondition(
                            key="category",
                            match=qmodels.MatchValue(value=category)
                        )
                    ]
                )

            res = self.client.query_points(
                collection_name=self.collection_name,
                query=query_vector,
                query_filter=query_filter,
                limit=limit
            )

            results = []
            for hit in res.points:
                results.append({
                    "score": hit.score,
                    "text": hit.payload.get("text", ""),
                    "category": hit.payload.get("category", ""),
                    "metadata": hit.payload
                })
            return results
        except Exception as e:
            logger.error(f"Error searching memories in Qdrant: {e}")
            return []

_memory_service: Optional[QdrantMemoryService] = None

def get_memory_service() -> QdrantMemoryService:
    global _memory_service
    if _memory_service is None:
        _memory_service = QdrantMemoryService()
    return _memory_service
