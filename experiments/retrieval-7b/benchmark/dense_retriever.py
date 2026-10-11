#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Stage 7B Dense Semantic Retriever
Supports:
1. BAAI/bge-small-zh-v1.5 (Dim: 512, Query instruction: 为这个句子生成表示以用于检索相关文档：)
2. intfloat/multilingual-e5-small (Dim: 384, Query: "query: ", Passage: "passage: ")

Features:
- Normalized L2 embeddings (Cosine similarity via dot product)
- Flat (single summary) vs Bounded Chunking strategy
- Aggregation by memoryId (max-pooling across chunks, preventing top-K pollution)
- Offline index serialization & disk size measurement
- Query latency & search latency separation
"""

import os
import time
import numpy as np
from typing import Dict, List, Any, Optional, Tuple
from sentence_transformers import SentenceTransformer

class DenseRetriever:
    def __init__(self, model_name: str = "BAAI/bge-small-zh-v1.5", strategy: str = "flat", similarity_threshold: float = 0.55):
        self.model_name = model_name
        self.strategy = strategy  # "flat" or "chunked"
        self.similarity_threshold = similarity_threshold
        
        print(f"Loading dense model: {model_name} (Strategy: {strategy})...")
        self.model = SentenceTransformer(model_name)
        
        # Configure model-specific prefixes
        if "bge" in model_name.lower():
            self.query_prefix = "为这个句子生成表示以用于检索相关文档："
            self.passage_prefix = ""
            self.default_threshold = 0.55
        elif "e5" in model_name.lower():
            self.query_prefix = "query: "
            self.passage_prefix = "passage: "
            self.default_threshold = 0.72
        else:
            self.query_prefix = ""
            self.passage_prefix = ""
            self.default_threshold = 0.50
            
        if similarity_threshold is not None:
            self.similarity_threshold = similarity_threshold
        else:
            self.similarity_threshold = self.default_threshold

        self.embeddings: Optional[np.ndarray] = None
        self.doc_ids: List[str] = [] # Aligned with rows of self.embeddings
        self.index_time_sec: float = 0.0
        self.index_size_bytes: int = 0

    def _chunk_text(self, text: str, chunk_size: int = 200, overlap: int = 50) -> List[str]:
        if len(text) <= chunk_size:
            return [text]
        chunks = []
        start = 0
        while start < len(text):
            end = min(start + chunk_size, len(text))
            chunk = text[start:end]
            if chunk.strip():
                chunks.append(chunk)
            if end >= len(text):
                break
            start += (chunk_size - overlap)
        return chunks

    def index_memories(self, memories: List[Dict[str, Any]]) -> Dict[str, Any]:
        start_t = time.perf_counter()
        
        passages: List[str] = []
        doc_ids: List[str] = []

        for m in memories:
            title = m.get("title") or ""
            text = m.get("rawText") or ""
            combined = f"{title}\n{text}".strip()

            if self.strategy == "chunked" and len(combined) > 300:
                chunks = self._chunk_text(combined, chunk_size=200, overlap=50)
                for c in chunks:
                    passages.append(f"{self.passage_prefix}{title}: {c}")
                    doc_ids.append(m["id"])
            else:
                passages.append(f"{self.passage_prefix}{combined}")
                doc_ids.append(m["id"])

        # Compute embeddings with batching and L2 normalization
        embeddings = self.model.encode(
            passages,
            batch_size=64,
            show_progress_bar=False,
            normalize_embeddings=True
        )

        self.embeddings = np.array(embeddings, dtype=np.float32)
        self.doc_ids = doc_ids
        self.index_time_sec = time.perf_counter() - start_t
        self.index_size_bytes = self.embeddings.nbytes

        return {
            "total_items": len(memories),
            "total_vectors": len(passages),
            "index_time_sec": self.index_time_sec,
            "index_size_mb": self.index_size_bytes / (1024 * 1024),
            "vector_dim": self.embeddings.shape[1]
        }

    def retrieve(self, query: str, top_k: int = 5) -> Dict[str, Any]:
        if self.embeddings is None or len(self.doc_ids) == 0:
            return {"query": query, "items": [], "scores": [], "latency_ms": 0.0}

        # 1. Encode query
        q_start = time.perf_counter()
        query_text = f"{self.query_prefix}{query}"
        q_vec = self.model.encode(
            [query_text],
            normalize_embeddings=True
        )[0]
        q_embed_ms = (time.perf_counter() - q_start) * 1000.0

        # 2. Vector search (cosine similarity = dot product of normalized vectors)
        s_start = time.perf_counter()
        scores = np.dot(self.embeddings, q_vec) # Shape: (N,)

        # 3. Aggregate by memoryId (max-pooling across chunks for chunked strategy)
        best_doc_scores: Dict[str, float] = {}
        for idx, doc_id in enumerate(self.doc_ids):
            score = float(scores[idx])
            if doc_id not in best_doc_scores or score > best_doc_scores[doc_id]:
                best_doc_scores[doc_id] = score
        v_search_ms = (time.perf_counter() - s_start) * 1000.0

        # Sort aggregated doc scores
        sorted_docs = sorted(best_doc_scores.items(), key=lambda x: x[1], reverse=True)

        # Apply similarity threshold for negative rejection
        filtered_docs = [
            (doc_id, score) for doc_id, score in sorted_docs
            if score >= self.similarity_threshold
        ]

        top_docs = filtered_docs[:top_k]

        return {
            "query": query,
            "items": [doc_id for doc_id, _ in top_docs],
            "scores": [score for _, score in top_docs],
            "top_candidate_pool": sorted_docs[:50], # for Hybrid union
            "q_embed_latency_ms": q_embed_ms,
            "v_search_latency_ms": v_search_ms,
            "total_latency_ms": q_embed_ms + v_search_ms
        }
