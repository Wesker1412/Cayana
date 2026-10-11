#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Stage 7B Benchmark Evaluator
Computes standard IR metrics:
- Recall@1
- Recall@5
- MRR@5
- False Positive Rate (on unanswerable/negative queries)
- Per-category breakdowns
- Identification of failure cases and qualitative highlights
"""

from typing import Dict, List, Any, Tuple

class BenchmarkEvaluator:
    @staticmethod
    def evaluate(queries: List[Dict[str, Any]], retriever: Any, retriever_name: str) -> Dict[str, Any]:
        results_per_query = []
        
        category_stats: Dict[str, Dict[str, Any]] = {}
        all_categories = set(q["category"] for q in queries)
        for cat in all_categories:
            category_stats[cat] = {
                "total": 0,
                "recall_at_1": 0,
                "recall_at_5": 0,
                "mrr_sum": 0.0,
                "false_positives": 0
            }

        total_queries = len(queries)
        positive_queries = 0
        negative_queries = 0
        
        overall_recall_1 = 0
        overall_recall_5 = 0
        overall_mrr = 0.0
        overall_fp = 0
        latencies = []

        for q in queries:
            q_id = q["query_id"]
            q_text = q["query_text"]
            cat = q["category"]
            targets = set(q.get("target_memory_ids", []))
            is_negative = len(targets) == 0

            res = retriever.retrieve(q_text, top_k=5)
            retrieved_ids = res["items"]
            latencies.append(res.get("latency_ms", res.get("total_latency_ms", 0.0)))

            category_stats[cat]["total"] += 1

            if is_negative:
                negative_queries += 1
                is_fp = len(retrieved_ids) > 0
                if is_fp:
                    overall_fp += 1
                    category_stats[cat]["false_positives"] += 1
                rec_1 = 0
                rec_5 = 0
                rr = 0.0
            else:
                positive_queries += 1
                # Check Recall@1
                rec_1 = 1 if (retrieved_ids and retrieved_ids[0] in targets) else 0
                # Check Recall@5
                rec_5 = 1 if any(rid in targets for rid in retrieved_ids[:5]) else 0
                
                # Check RR
                rr = 0.0
                for rank, rid in enumerate(retrieved_ids[:5], start=1):
                    if rid in targets:
                        rr = 1.0 / rank
                        break

                overall_recall_1 += rec_1
                overall_recall_5 += rec_5
                overall_mrr += rr

                category_stats[cat]["recall_at_1"] += rec_1
                category_stats[cat]["recall_at_5"] += rec_5
                category_stats[cat]["mrr_sum"] += rr

            results_per_query.append({
                "query_id": q_id,
                "query_text": q_text,
                "category": cat,
                "targets": list(targets),
                "retrieved": retrieved_ids,
                "recall_1": rec_1,
                "recall_5": rec_5,
                "rr": rr,
                "is_fp": len(retrieved_ids) > 0 if is_negative else False,
                "latency_ms": res.get("latency_ms", res.get("total_latency_ms", 0.0))
            })

        avg_recall_1 = overall_recall_1 / positive_queries if positive_queries > 0 else 0.0
        avg_recall_5 = overall_recall_5 / positive_queries if positive_queries > 0 else 0.0
        avg_mrr_5 = overall_mrr / positive_queries if positive_queries > 0 else 0.0
        fp_rate = overall_fp / negative_queries if negative_queries > 0 else 0.0
        avg_latency = sum(latencies) / len(latencies) if latencies else 0.0

        # Category breakdown metrics
        cat_summary = {}
        for cat, stats in category_stats.items():
            tot = stats["total"]
            if cat == "unanswerable_negative":
                cat_summary[cat] = {
                    "total": tot,
                    "false_positive_rate": stats["false_positives"] / tot if tot > 0 else 0.0,
                    "false_positives": stats["false_positives"]
                }
            else:
                cat_summary[cat] = {
                    "total": tot,
                    "recall_at_1": stats["recall_at_1"] / tot if tot > 0 else 0.0,
                    "recall_at_5": stats["recall_at_5"] / tot if tot > 0 else 0.0,
                    "mrr_5": stats["mrr_sum"] / tot if tot > 0 else 0.0
                }

        return {
            "retriever_name": retriever_name,
            "total_queries": total_queries,
            "positive_queries": positive_queries,
            "negative_queries": negative_queries,
            "recall_at_1": avg_recall_1,
            "recall_at_5": avg_recall_5,
            "mrr_at_5": avg_mrr_5,
            "false_positive_rate": fp_rate,
            "avg_latency_ms": avg_latency,
            "category_summary": cat_summary,
            "query_results": results_per_query
        }
