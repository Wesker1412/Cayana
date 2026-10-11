# Cayana Stage 7B-1: Offline Semantic Retrieval Benchmark

本目錄包含 Cayana Stage 7B-1 離線語意檢索效能評測研究的所有程式碼、資料集、評測工具與報告。

> **嚴格邊界宣告**：
> - 本研究完全隔離於 `experiments/retrieval-7b/` 目錄內，位於獨立研究分支 `experiment/stage7b-retrieval-benchmark`。
> - 未修改任何 Android production app 程式碼、Room schema、Gradle 設定或 Stage 0–7A 實作。
> - 不包含任何真機 APK 整合、雲端資料庫（Supabase）、外部伺服器或 LLM 產生程式碼。
> - 所有記憶均為合成日常資料（無任何個人隱私資料）。

---

## 目錄架構

```text
experiments/retrieval-7b/
├── README.md                          # 本說明文件
├── dataset/
│   ├── generate_dataset.py            # 100% 可重現資料集生成腳本 (固定 random.seed(42))
│   ├── memories.json                  # 2,000 筆合成日常記憶
│   └── queries.json                   # 150 道獨立標註答案之自然語言評測問題
├── benchmark/
│   ├── stage7a_retriever.py           # Stage 7A 詞法倒排檢索 1:1 精確復刻 (SQLite FTS4)
│   ├── dense_retriever.py             # 語意模型推論與向量檢索 (BGE / E5，支援 Flat 與 Chunked)
│   ├── hybrid_retriever.py            # 詞法與語意混合檢索 (Candidate Union + RRF 融合)
│   ├── evaluator.py                   # IR 指標計算器 (Recall@1, Recall@5, MRR@5, FP Rate)
│   └── run_benchmark.py               # Master 執行器 (Smoke test + 全量 Benchmark)
└── reports/
    ├── STAGE7B_1_BENCHMARK.md         # 完整工程與決策研究報告
    ├── results.csv                    # 全組態各指標量化對比表
    └── failures.md                    # 質性失敗案例、互補案例與誤召案例深度分析
```

---

## 執行方式 (How to Run)

### 1. 安裝環境依賴 (Python 3.11+)
```bash
pip install sentence-transformers transformers torch onnxruntime numpy scikit-learn
```

### 2. 生成評測資料集
```bash
python experiments/retrieval-7b/dataset/generate_dataset.py
```

### 3. 執行完整評測 (含 Smoke Test 與 全量 Benchmark)
```bash
python experiments/retrieval-7b/benchmark/run_benchmark.py
```

評測完成後將自動在 `reports/` 目錄輸出：
- `results.csv`：數值指標一覽表。
- `failures.md`：具體檢索失敗與對比案例。
- `STAGE7B_1_BENCHMARK.md`：完整工程分析報告。
