#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Stage 7B Hybrid Retriever (FTS + Embedding Fusion)
Combines Stage 7A deterministic lexical candidates and Dense semantic candidates.

CRITICAL REQUIREMENT:
C MUST be capable of retrieving memories that Scheme A completely missed.
It does NOT merely rerank Scheme A's candidates.
Instead, it takes the UNION of candidates from both:
Candidates_Union = Candidates_Stage7A U Candidates_Dense (top-50)
and merges them via Reciprocal Rank Fusion (RRF) with semantic thresholding.
"""

import time
from typing import Dict, List, Any
try:
    from .stage7a_retriever import Stage7ARetriever
    from .dense_retriever import DenseRetriever
except ImportError:
    from stage7a_retriever import Stage7ARetriever
    from dense_retriever import DenseRetriever

class HybridRetriever:
    def __init__(self, stage7a: Stage7ARetriever, dense: DenseRetriever, rrf_k: int = 60, weight_lex: float = 0.5, weight_dense: float = 0.5):
        self.stage7a = stage7a
        self.dense = dense
        self.rrf_k = rrf_k
        self.weight_lex = weight_lex
        self.weight_dense = weight_dense

    def retrieve(self, query: str, top_k: int = 5) -> Dict[str, Any]:
        start_t = time.perf_counter()

        # 1. Run Stage 7A Lexical Retrieval
        res_7a = self.stage7a.retrieve(query, top_k=50) # Get candidate ranks from 7A
        lex_items = res_7a["items"] # Ranked list of IDs

        # 2. Run Dense Semantic Retrieval
        res_dense = self.dense.retrieve(query, top_k=50) # Get candidate ranks from Dense
        dense_candidates = res_dense.get("top_candidate_pool", []) # List of (doc_id, score)
        dense_items = [doc_id for doc_id, _ in dense_candidates if doc_id in res_dense["items"] or dense_candidates]

        # 3. Form Union of Candidates (Guarantees Dense-only items enter pool!)
        all_candidate_ids = list(dict.fromkeys(lex_items + [doc_id for doc_id, _ in dense_candidates[:50]]))

        if not all_candidate_ids:
            return {
                "query": query,
                "items": [],
                "scores": [],
                "latency_ms": (time.perf_counter() - start_t) * 1000.0,
                "recall_source": "none"
            }

        # 4. Compute Reciprocal Rank Fusion (RRF)
        # RRF(d) = w_lex / (k + rank_lex) + w_dense / (k + rank_dense)
        lex_rank_map = {doc_id: i + 1 for i, doc_id in enumerate(lex_items)}
        dense_rank_map = {doc_id: i + 1 for i, (doc_id, _) in enumerate(dense_candidates[:50])}
        dense_score_map = {doc_id: score for doc_id, score in dense_candidates[:50]}

        scored_candidates = []
        for doc_id in all_candidate_ids:
            score = 0.0
            in_lex = doc_id in lex_rank_map
            in_dense = doc_id in dense_rank_map
            
            if in_lex:
                score += self.weight_lex / (self.rrf_k + lex_rank_map[doc_id])
            if in_dense:
                # Modulate by cosine similarity confidence
                dense_cos = dense_score_map.get(doc_id, 0.0)
                if dense_cos >= self.dense.similarity_threshold:
                    score += self.weight_dense / (self.rrf_k + dense_rank_map[doc_id])

            if score > 0:
                scored_candidates.append((doc_id, score, in_lex, in_dense))

        # Sort by RRF score descending
        scored_candidates.sort(key=lambda x: x[1], reverse=True)
        top_items = scored_candidates[:top_k]

        return {
            "query": query,
            "items": [doc_id for doc_id, _, _, _ in top_items],
            "scores": [score for _, score, _, _ in top_items],
            "details": top_items,
            "latency_ms": (time.perf_counter() - start_t) * 1000.0
        }
