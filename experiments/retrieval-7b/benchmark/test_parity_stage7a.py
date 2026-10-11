#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Parity Tests for Stage 7A Python Surrogate vs Kotlin Specification
Validates that the Python surrogate implementation adheres to the core rules of
Cayana Stage 7A:
1. CJK and Latin token generation
2. Date token generation
3. Bounded candidate pool (max 50)
4. Title match prioritization over text-only match
5. Negative rejection (confidence NONE, empty retrieved items)
"""

import unittest
from stage7a_retriever import (
    Stage7APythonSurrogate,
    tokenize_latin_and_cjk,
    add_date_tokens
)

class TestStage7AParity(unittest.TestCase):
    def test_tokenization_cjk_and_latin(self):
        tokens = set()
        tokenize_latin_and_cjk("Cayana 台北高鐵 2026", tokens)
        self.assertIn("cayana", tokens)
        self.assertIn("2026", tokens)
        # CJK unigrams
        self.assertIn("台", tokens)
        self.assertIn("北", tokens)
        self.assertIn("高", tokens)
        self.assertIn("鐵", tokens)
        # CJK bigrams
        self.assertIn("台北", tokens)
        self.assertIn("北高", tokens)
        self.assertIn("高鐵", tokens)

    def test_date_tokens(self):
        # 2026-10-15 12:00:00 UTC = 1792065600000 ms
        tokens = set()
        add_date_tokens(1792065600000, tokens)
        # Check standard date tokens
        self.assertTrue(any(t.startswith("2026") for t in tokens))
        self.assertTrue(any("月" in t for t in tokens))
        self.assertTrue(any("日" in t or "號" in t for t in tokens))

    def test_bounded_candidate_pool(self):
        retriever = Stage7APythonSurrogate()
        # Insert 100 memories with the word "城堡"
        memories = [
            {
                "id": f"mem-{i:04d}",
                "sourceType": "SCREENSHOT",
                "createdAt": 1700000000000 + i * 1000,
                "capturedAt": 1700000000000 + i * 1000,
                "title": f"普通記事 {i}",
                "rawText": f"今天在路上看到一座美麗的城堡，照片編號 {i}",
                "normalizedText": f"今天在路上看到一座美麗的城堡，照片編號 {i}",
                "sourceUri": f"content://test/{i}",
                "sourceUrl": None
            }
            for i in range(100)
        ]
        retriever.index_memories(memories)
        res = retriever.retrieve("城堡", top_k=5)
        # Should return bounded top 5
        self.assertEqual(len(res["items"]), 5)
        self.assertIn(res["confidence"], ["HIGH", "MEDIUM", "LOW"])

    def test_exact_title_prioritization(self):
        retriever = Stage7APythonSurrogate()
        # Older memory with exact title
        old_exact = {
            "id": "mem-exact-title",
            "sourceType": "SCREENSHOT",
            "createdAt": 1000000000000,
            "capturedAt": 1000000000000,
            "title": "城堡",
            "rawText": "這是古老的紀錄",
            "normalizedText": "這是古老的紀錄",
            "sourceUri": "content://test/exact",
            "sourceUrl": None
        }
        # Newer memory with text match
        new_text_match = {
            "id": "mem-newer-text",
            "sourceType": "SCREENSHOT",
            "createdAt": 2000000000000,
            "capturedAt": 2000000000000,
            "title": "現代旅行札記",
            "rawText": "今天參觀了德國的新天鵝城堡",
            "normalizedText": "今天參觀了德國的新天鵝城堡",
            "sourceUri": "content://test/newer",
            "sourceUrl": None
        }
        retriever.index_memories([old_exact, new_text_match])
        res = retriever.retrieve("城堡", top_k=5)
        # Exact title must be Top-1 despite being older
        self.assertEqual(res["items"][0], "mem-exact-title")

    def test_negative_abstain(self):
        retriever = Stage7APythonSurrogate()
        memory = {
            "id": "mem-01",
            "sourceType": "SCREENSHOT",
            "createdAt": 1700000000000,
            "capturedAt": 1700000000000,
            "title": "全聯福利中心購物收據",
            "rawText": "購買義美鮮乳與白土司",
            "normalizedText": "購買義美鮮乳與白土司",
            "sourceUri": "content://test/01",
            "sourceUrl": None
        }
        retriever.index_memories([memory])
        res = retriever.retrieve("尋找完全不存在的火星探測船發射代號", top_k=5)
        self.assertEqual(res["confidence"], "NONE")
        self.assertEqual(len(res["items"]), 0)

if __name__ == "__main__":
    unittest.main()
