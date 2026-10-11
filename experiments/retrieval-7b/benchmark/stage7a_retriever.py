#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Stage 7A Deterministic Retrieval Engine (Python Exact Replica)
Faithfully replicates Cayana Stage 7A production architecture:
- MemorySearchDocumentBuilder: CJK n-grams + Latin tokens + Date tokens
- SQLite FTS4 table memories_fts + canonical memories table
- Bounded candidate selection (Tiers 1-4 with SQL LIMIT)
- Deterministic relevance scoring (EXACT_TITLE, TITLE_MATCH, RAW_TEXT, etc.)
- Top-K bounding & Confidence computation
"""

import re
import sqlite3
import time
from datetime import datetime
from typing import Dict, List, Any, Optional, Tuple, Set

CHINESE_MONTHS = [
    "一月", "二月", "三月", "四月", "五月", "六月",
    "七月", "八月", "九月", "十月", "十一月", "十二月"
]

def is_cjk(ch: str) -> bool:
    code = ord(ch)
    return (
        (0x4E00 <= code <= 0x9FFF) or
        (0x3400 <= code <= 0x4DBF) or
        (0x20000 <= code <= 0x2A6DF) or
        (0xF900 <= code <= 0xFAFF)
    )

def tokenize_latin_and_cjk(text: str, out_tokens: Set[str]):
    if not text:
        return
    # Latin / Alphanumeric
    for match in re.finditer(r"[a-zA-Z0-9]+", text):
        out_tokens.add(match.group(0).lower())
        
    # CJK unigrams and bigrams
    cjk_buf = []
    for ch in text:
        if is_cjk(ch):
            cjk_buf.append(ch)
        else:
            if cjk_buf:
                seq = "".join(cjk_buf)
                for char in seq:
                    out_tokens.add(char)
                for i in range(len(seq) - 1):
                    out_tokens.add(seq[i:i+2])
                cjk_buf = []
    if cjk_buf:
        seq = "".join(cjk_buf)
        for char in seq:
            out_tokens.add(char)
        for i in range(len(seq) - 1):
            out_tokens.add(seq[i:i+2])

def add_date_tokens(ts: int, tokens: Set[str]):
    if ts <= 0:
        return
    dt = datetime.fromtimestamp(ts / 1000.0)
    iso = dt.strftime("%Y-%m-%d")
    short_d = dt.strftime("%m/%d")
    year = dt.strftime("%Y")
    month_num = dt.month
    day_num = dt.day

    tokens.add(iso)
    tokens.add(short_d)
    tokens.add(year)
    tokens.add(f"{year}年")
    tokens.add(f"{month_num}月")
    tokens.add(f"{year}年{month_num}月")
    zh_month = CHINESE_MONTHS[month_num - 1]
    tokens.add(zh_month)
    tokens.add(f"{year}年{zh_month}")
    tokens.add(f"{day_num}日")
    tokens.add(f"{day_num}號")

class Stage7ARetriever:
    def __init__(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self._init_db()
        self.indexed_count = 0

    def _init_db(self):
        cur = self.conn.cursor()
        cur.execute("""
            CREATE TABLE memories (
                id TEXT PRIMARY KEY,
                sourceType TEXT,
                createdAt INTEGER,
                capturedAt INTEGER,
                title TEXT,
                rawText TEXT,
                normalizedText TEXT,
                sourceUri TEXT,
                sourceUrl TEXT,
                metadataJson TEXT,
                entitiesJson TEXT
            )
        """)
        cur.execute("""
            CREATE VIRTUAL TABLE memories_fts USING fts4(
                memoryId,
                title,
                rawText,
                normalizedText,
                sourceType,
                sourceUrl,
                host,
                displayName,
                searchTokens
            )
        """)
        self.conn.commit()

    def build_search_tokens(self, memory: Dict[str, Any]) -> str:
        tokens: Set[str] = set()
        stype = memory.get("sourceType", "SCREENSHOT")
        tokens.add(stype.lower())
        
        # CapturedAt date tokens
        add_date_tokens(memory.get("capturedAt", 0), tokens)
        
        # Source URL
        url = memory.get("sourceUrl")
        if url:
            tokenize_latin_and_cjk(url, tokens)
            
        # Display name / title
        title = memory.get("title")
        if title:
            tokenize_latin_and_cjk(title, tokens)
            
        # Text
        raw_text = memory.get("rawText")
        if raw_text:
            tokenize_latin_and_cjk(raw_text, tokens)
            
        # Entities
        for e in memory.get("entities", []):
            tokenize_latin_and_cjk(e, tokens)

        return " ".join(tokens)

    def index_memories(self, memories: List[Dict[str, Any]]):
        cur = self.conn.cursor()
        for m in memories:
            cur.execute("""
                INSERT OR REPLACE INTO memories (
                    id, sourceType, createdAt, capturedAt, title, rawText, normalizedText, sourceUri, sourceUrl, metadataJson, entitiesJson
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                m["id"],
                m["sourceType"],
                m["createdAt"],
                m["capturedAt"],
                m.get("title") or "",
                m.get("rawText") or "",
                m.get("normalizedText") or "",
                m.get("sourceUri"),
                m.get("sourceUrl"),
                "",
                ""
            ))
            
            search_tokens = self.build_search_tokens(m)
            cur.execute("""
                INSERT OR REPLACE INTO memories_fts (
                    memoryId, title, rawText, normalizedText, sourceType, sourceUrl, host, displayName, searchTokens
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                m["id"],
                m.get("title") or "",
                m.get("rawText") or "",
                m.get("normalizedText") or "",
                m["sourceType"],
                m.get("sourceUrl") or "",
                "",
                m.get("title") or "",
                search_tokens
            ))
        self.conn.commit()
        self.indexed_count += len(memories)

    def analyze_query(self, query: str) -> Dict[str, Any]:
        sanitized = re.sub(r'["*\\:()^~?？！!，,。.\-]', " ", query).strip()
        stop_words = {"我", "你", "他", "她", "它", "的", "了", "在", "是", "有", "個", "和", "就", "不", "人", "都", "一", "一個", "上", "也", "很", "到", "說", "要", "去", "會", "著", "沒有", "什麼", "請", "幫我", "查", "找"}
        
        words = re.findall(r"[a-zA-Z0-9]+", sanitized)
        cjk_chars = [ch for ch in sanitized if is_cjk(ch)]
        
        content_terms = []
        for w in words:
            if len(w) >= 2 and w.lower() not in stop_words:
                content_terms.append(w.lower())
                
        # Group CJK characters into 2-char tokens or 1-char if single
        if cjk_chars:
            seq = "".join(cjk_chars)
            # Simple segmentation
            i = 0
            while i < len(seq):
                if i + 2 <= len(seq):
                    bigram = seq[i:i+2]
                    if bigram not in stop_words:
                        content_terms.append(bigram)
                    i += 2
                else:
                    if seq[i] not in stop_words:
                        content_terms.append(seq[i])
                    i += 1
                    
        content_terms = list(dict.fromkeys(content_terms))[:8]
        return {
            "raw": query,
            "content_terms": content_terms,
            "is_empty": len(content_terms) == 0
        }

    def _build_fts_expr(self, term: str) -> str:
        clean = re.sub(r'["*\\:()^~\-]', "", term).strip()
        if not clean:
            return ""
        if all(is_cjk(c) for c in clean):
            return clean
        return f"{clean}*"

    def _execute_fts_query(self, fts_expr: str, limit: int = 15, preferred_term: str = "") -> List[Dict[str, Any]]:
        if not fts_expr:
            return []
        cur = self.conn.cursor()
        sql = """
            SELECT memories.* FROM memories
            JOIN memories_fts ON memories.id = memories_fts.memoryId
            WHERE memories_fts MATCH ?
            ORDER BY (
                CASE
                    WHEN ? != '' AND memories.title = ? THEN 3
                    WHEN ? != '' AND memories.title LIKE ? || '%' THEN 2
                    WHEN ? != '' AND memories.title LIKE '%' || ? || '%' THEN 1
                    ELSE 0
                END
            ) DESC, memories.capturedAt DESC
            LIMIT ?
        """
        try:
            cur.execute(sql, (fts_expr, preferred_term, preferred_term, preferred_term, preferred_term, preferred_term, preferred_term, limit))
            rows = cur.fetchall()
            return [dict(r) for r in rows]
        except Exception:
            return []

    def retrieve(self, query: str, top_k: int = 5) -> Dict[str, Any]:
        start_t = time.perf_counter()
        analyzed = self.analyze_query(query)
        
        if analyzed["is_empty"]:
            return {
                "query": query,
                "items": [],
                "confidence": "NONE",
                "latency_ms": (time.perf_counter() - start_t) * 1000.0
            }

        candidates: List[Dict[str, Any]] = []
        seen_ids = set()

        # Tier 1: Title & Compound Matches
        for term in analyzed["content_terms"][:2]:
            expr = self._build_fts_expr(term)
            matches = self._execute_fts_query(expr, limit=10, preferred_term=term)
            for m in matches:
                if term.lower() in (m["title"] or "").lower():
                    if m["id"] not in seen_ids:
                        seen_ids.add(m["id"])
                        candidates.append(m)

        if len(analyzed["content_terms"]) > 1:
            and_expr = " ".join([self._build_fts_expr(t) for t in analyzed["content_terms"][:4] if self._build_fts_expr(t)])
            matches = self._execute_fts_query(and_expr, limit=20)
            for m in matches:
                if m["id"] not in seen_ids:
                    seen_ids.add(m["id"])
                    candidates.append(m)

        # Tier 2: Individual terms
        for term in analyzed["content_terms"][:4]:
            expr = self._build_fts_expr(term)
            matches = self._execute_fts_query(expr, limit=15, preferred_term=term)
            for m in matches:
                if m["id"] not in seen_ids:
                    seen_ids.add(m["id"])
                    candidates.append(m)

        # Tier 3: Fallback LIKE if no candidates found
        if not candidates and analyzed["content_terms"]:
            for term in analyzed["content_terms"][:2]:
                cur = self.conn.cursor()
                cur.execute("""
                    SELECT * FROM memories
                    WHERE (rawText LIKE '%' || ? || '%' OR title LIKE '%' || ? || '%')
                    ORDER BY (CASE WHEN title = ? THEN 3 WHEN title LIKE ? || '%' THEN 2 WHEN title LIKE '%' || ? || '%' THEN 1 ELSE 0 END) DESC, capturedAt DESC
                    LIMIT 10
                """, (term, term, term, term, term))
                for row in cur.fetchall():
                    m = dict(row)
                    if m["id"] not in seen_ids:
                        seen_ids.add(m["id"])
                        candidates.append(m)

        # Bounded candidate pool (max 50)
        bounded_candidates = candidates[:50]

        # Scoring & Reranking
        scored_items = []
        for item in bounded_candidates:
            score = 0.0
            t_lower = (item["title"] or "").lower()
            r_lower = (item["rawText"] or "").lower()

            has_thematic = False
            for term in analyzed["content_terms"]:
                if t_lower == term:
                    score += 100.0
                    has_thematic = True
                elif term in t_lower:
                    score += 70.0
                    has_thematic = True
                elif term in r_lower:
                    score += 15.0
                    has_thematic = True

            scored_items.append((score, has_thematic, item))

        scored_items.sort(key=lambda x: x[0], reverse=True)
        top_items = scored_items[:top_k]

        # Confidence
        top_score = top_items[0][0] if top_items else 0.0
        has_thematic_top = top_items[0][1] if top_items else False
        
        if not top_items or top_score <= 0.0:
            confidence = "NONE"
        elif not has_thematic_top:
            confidence = "NONE"
        elif top_score >= 80.0:
            confidence = "HIGH"
        elif top_score >= 40.0:
            confidence = "MEDIUM"
        else:
            confidence = "LOW"

        latency = (time.perf_counter() - start_t) * 1000.0
        
        # If confidence is NONE, return empty items to indicate no valid candidate
        retrieved_ids = [item["id"] for score, _, item in top_items if score > 0] if confidence != "NONE" else []

        return {
            "query": query,
            "items": retrieved_ids,
            "scores": [score for score, _, _ in top_items if score > 0] if confidence != "NONE" else [],
            "confidence": confidence,
            "latency_ms": latency
        }
