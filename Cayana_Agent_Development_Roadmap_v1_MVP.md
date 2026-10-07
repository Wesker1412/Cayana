# Cayana v1 MVP — Agent 分階段開發任務與驗收標準
## v1.0

> 本文件取代 `Cayana_Agent_Development_Roadmap_v0.2.md` 作為 v1 MVP 的主要開發協議。

---

# 0. 使用方式

每次只執行一個 Stage。

完成後：

1. 停止。
2. commit / push GitHub。
3. 回報 branch / SHA。
4. 回報完成內容。
5. 回報測試。
6. 回報實機驗證。
7. 回報已知問題。
8. 等待第二個 AI 驗收。
9. 未得到批准不得進下一 Stage。

---

# 1. v1 產品邊界

產品：

**Cayana — Personal Memory Layer**

核心：

> **Capture / Retrieve / Ask / Act**

v1 canonical source of truth：

> **Local Memory Database**

Google Drive：

> **Encrypted Backup / Restore**

Cayana Server：

> **LLM inference only，不是 Memory Cloud**

外部服務：

> **Destination，不是 Cayana dependency**

---

# 2. 全域硬性規則

所有 Stage 都必須遵守：

1. Local-first。
2. 解析失敗不能造成 Memory 保存失敗。
3. 外部服務失敗不能造成 Memory 保存失敗。
4. 原始媒體刪除不能刪除 Memory。
5. LLM 永遠不能直接讀 DB。
6. LLM 只能取得 Local Retrieval 已授權的 Top-K。
7. 原始 media 預設不上傳 Cayana Server。
8. Cayana Server 不保存完整 Memory DB。
9. 一般 log / crash / analytics 不含 Memory plaintext。
10. Recovery Key 不上傳。
11. Calendar / Task / Notion credentials 不交給 LLM。
12. 不做高頻 polling。
13. 不做常駐大型 LLM。
14. canonical Memory mutation 必須有明確 write path。
15. 每個 Stage 都必須有 tests。
16. 不為未來 Agent 過度設計。
17. 不自行跨 Stage。
18. 不重新加入 Cayana Cloud / Supabase Memory Sync 到 v1。

---

# 3. 已完成 Stage

## Stage 0 — Foundation
**PASS / CLOSED**

包含：

- Kotlin / Compose
- Room
- DI
- logging policy
- test framework
- CI
- launch smoke test

不重新開啟，除非後續 regression。

---

## Stage 1 — Onboarding / Permissions / Sources
**PASS / CLOSED**

包含：

- Screenshot / Photo / Recording / Downloads
- minimum permissions
- permission denial safety
- calendar selection
- settings persistence

不重新開啟，除非 regression。

---

## Stage 2 — Screenshot → OCR → Memory
**PASS / CLOSED**

包含：

- screenshot detection
- local OCR
- dedup
- durable processing
- notification privacy
- process restart reliability

不重新開啟，除非 regression。

---

## Stage 3 — Screenshot → Calendar
**PASS / CLOSED**

包含：

- deterministic date/time extraction
- confidence policy
- durable calendar action state
- process-death reconciliation
- notification / Undo
- exactly-once behavior

不重新開啟，除非 regression。

---

## Stage 4 — Photos / Recordings
**PASS / CLOSED**

包含：

- Photo ingest
- local OCR
- Recording ingest
- sherpa-onnx local STT
- long recording chunking / resume
- battery constraints
- typed source existence

不重新開啟，除非 regression。

---

## Stage 5 — Share Sheet / Search / Recent
**PASS / CLOSED**

包含：

- Shared Text / URL / Image / PDF
- durable share receipts
- local FTS
- Search repair
- Recent / Detail
- rebuild concurrency safety

不重新開啟，除非 regression。

---

## Stage 6 — Google Drive Encrypted Backup / Restore
**CODE PASS**

已完成：

- client-side encrypted logical backup
- Recovery Key
- resumable upload
- typed restore
- calendar history isolation
- multi-hop restore history
- concurrency protection
- wrong-key fail-closed
- restart revoke behavior
- CI

狀態：

> **Code PASS / Real Google Drive E2E deferred**

最終 Codex acceptance 必須補：

- real Drive auth
- real backup
- ciphertext plaintext scan
- pm clear
- fresh restore
- wrong key
- Calendar Provider count
- revoke
- Logcat privacy scan

目前不再修改 Stage 6，除非實際 runtime 出現 bug。

---

# 4. 已取消的舊 Stage 7

舊 Stage 7：

> Cayana Cloud / Supabase encrypted Memory sync

不屬於 v1 MVP。

不得繼續 production 化。

相關成果應移至：

```text
future-reference/post-v1/cayana-cloud-sync/
```

供未來研究。

v1 production 不依賴：

- Supabase
- Cloud Memory
- anonymous tenant sync
- multi-device Cayana sync
- Cayana account ecosystem

---

# Stage 7 — Local Retrieval Layer

## 目標

建立 Cayana Ask 與未來任何 LLM provider 都能共用的本機 Memory Retrieval Layer。

本 Stage 不接正式 LLM。

---

## 7.1 Query Pipeline

建立：

```text
Question
→ Query normalization
→ deterministic filters
→ FTS candidates
→ optional semantic candidates
→ local rerank
→ Top-K MemoryContext
```

Search UI 現有 FTS 不得被破壞。

---

## 7.2 Retrieval API

建立清楚 interface，例如：

```kotlin
interface MemoryRetriever {
    suspend fun retrieve(
        query: String,
        options: RetrievalOptions
    ): RetrievalResult
}
```

輸出應是小型、安全 context：

```text
MemoryContext
- memoryId
- sourceType
- capturedAt
- title
- relevantExcerpt
- sourceUrl?
- relevance
```

不要把整筆 Room Entity 或 Database handle 暴露給 AI layer。

---

## 7.3 Lightweight Semantic Retrieval

如果加入 embedding：

- on-device
- small model
- bounded memory / CPU
- rebuildable
- 不進 Google Drive backup
- embedding index 可以重建
- 不做 cloud vector DB

不得因 embedding model 不可用而破壞 FTS。

Fallback：

> FTS only。

---

## 7.4 Retrieval Privacy

Retriever 只能讀本機 Memory。

不得：

- upload whole DB
- return unbounded result
- allow arbitrary SQL
- expose keys
- expose Calendar credentials

Top-K 集中在 config。

---

## 7.5 Tests

至少：

```text
ftsOnlyRetrievalWorks
semanticRetrievalImprovesRelatedQuery
retrievalReturnsBoundedTopK
retrievalDoesNotExposeDatabaseHandle
deletedSourceMemoryStillRetrievable
noRelevantMemoryReturnsEmptyOrLowConfidence
embeddingUnavailableFallsBackToFts
```

---

## Stage 7 驗收

準備不同 source：

- Screenshot
- Photo
- Recording
- Shared Text
- Shared URL

詢問與其中一筆相關的自然語句。

驗證：

- 正確 Memory 進 Top-K
- 無關 Memory 不大量洩漏
- Top-K 有硬上限
- Search regression PASS
- Calendar 不受影響
- offline 可完整使用 Retrieval

完成後停止。

---

# Stage 8 — Cayana Ask + LLM Inference Boundary

## 目標

完成：

> **Local Retrieval → Cayana LLM → Answer + Sources**

不建立 Cayana Cloud Memory。

---

## 8.1 Ask UI

Home：

```text
想找什麼？
[ 問你的記憶…… ]
```

回答畫面需顯示：

- answer
- cited Memory sources
- loading
- retry
- no relevant memory
- network unavailable

---

## 8.2 Request Boundary

流程固定：

```text
Question
↓
MemoryRetriever
↓
Top-K MemoryContext
↓
ContextPack
↓
LLMProvider
↓
Answer
```

LLMProvider 不可取得：

- MemoryRepository
- MemoryDao
- Room DB
- RecoveryKey
- CalendarProvider
- Destination tokens

---

## 8.3 Cayana LLM API

建立 provider abstraction：

```kotlin
interface LlmProvider {
    suspend fun answer(
        question: String,
        context: ContextPack
    ): LlmAnswer
}
```

第一個 production provider：

> Cayana LLM API

Server request 只包含：

- opaque installation credential
- question
- bounded Top-K context
- request metadata 必要最小值

---

## 8.4 Server Privacy

Server 不長期保存完整 context。

禁止正常 log：

- question plaintext
- OCR plaintext
- transcript plaintext
- source URLs
- context contents

Operational logs：

- request ID
- latency
- token counts
- model
- HTTP status
- coarse error code

---

## 8.5 No Account Requirement

v1 不要求：

- email
- phone
- username
- password

可使用匿名 installation credential 做：

- quota
- abuse prevention
- rate limit

Auth failure 不得造成 Memory 損失。

---

## 8.6 Hallucination Policy

如果 Top-K 不足：

回答應能說：

> 找不到足夠相關的記憶。

不得硬猜。

LLM answer 必須附 Memory source refs。

---

## 8.7 Prompt Injection Boundary

Memory 中可能包含：

> Ignore previous instructions...

這只是資料。

不得因此：

- 擴大 retrieval scope
- 讀其他 Memory
- 操作 Calendar
- 操作 Tasks
- 操作 Notion
- 改 DB

---

## 8.8 Tests

至少：

```text
askSendsOnlyRetrievedTopK
llmProviderCannotAccessRepository
answerIncludesMemorySources
noRelevantMemoryDoesNotHallucinate
networkFailureKeepsLocalSearchAvailable
contextPackHasHardSizeLimit
memoryPromptInjectionCannotExpandCapabilities
sensitiveContextNotWrittenToLogs
```

---

## Stage 8 Runtime Acceptance

建立：

> XX Live / 10-18 / 19:30 / 台北流行音樂中心

詢問：

> 我十月是不是有一場演唱會？

驗證：

- local retrieval 正確
- 只有必要 Top-K 出站
- server 正常回答
- source 可點回 Memory
- 關網後 Ask 顯示合理錯誤
- local Search 仍正常

完成後停止。

---

# Stage 9 — Action Router / Tasks / Notion

## 目標

把目前 Calendar 特例擴展成通用：

> **Understand → Route → Destination**

Calendar 現有可靠流程不得重寫成較弱版本。

---

## 9.1 Action Model

建立通用概念：

```text
ActionCandidate
- type
- title
- due/start/end
- location
- notes
- confidence
- sourceMemoryId
```

至少：

```text
CALENDAR_EVENT
TASK
NOTE
```

---

## 9.2 Routing Policy

流程：

```text
Memory
→ deterministic / local classification
→ ActionCandidate
→ confidence
→ configured Destination
```

規則：

- Destination 不存在 → Memory only
- Destination auth 失效 → Memory only + needs attention
- Action failure → Memory 不 rollback
- false positive 風險高 → ask first

---

## 9.3 Tasks Provider

建立 provider abstraction：

```kotlin
interface TaskDestination {
    suspend fun createTask(...)
}
```

v1 至少完成一個 production-ready provider。

其他 provider 可以保留 interface / future adapter。

Task creation 需要：

- title
- due date/time（若有）
- notes / source ref
- idempotency
- retry boundary
- duplicate protection

---

## 9.4 Notion Destination

Notion 是：

> 知識 / 筆記 destination

不是 Cayana DB。

支援：

- OAuth
- 使用者選擇 target page/database
- Create Page / record
- title
- structured body
- source metadata
- Cayana Memory reference

不得：

- 把全部 Memory 一次同步 Notion
- 默認自動傳所有私人資料

設定至少：

```text
OFF
ASK_EACH_TIME
AUTO_FOR_SELECTED_TYPES
```

---

## 9.5 OAuth Broker

如果 Notion OAuth 需要 client secret：

建立最小 server-side OAuth broker。

Broker：

- only token exchange
- no Memory storage
- no LLM
- no Cayana Cloud DB

Secret 不得進 Android APK。

---

## 9.6 Destination Credentials

Token：

- Android secure storage
- no plaintext logs
- least privilege
- disconnect / revoke handling

---

## 9.7 Tests

至少：

```text
destinationFailureNeverLosesMemory
taskCandidateRoutesToConfiguredTaskProvider
taskDuplicateRetryIsIdempotent
noTaskProviderFallsBackToMemoryOnly
noteCandidateCanRequireConfirmation
notionDisabledNeverUploadsContent
notionTokenNeverAppearsInLogs
calendarRegressionStillExactlyOnce
```

---

## Stage 9 Runtime Acceptance

至少：

1. 一筆事件 → Calendar
2. 一筆待辦 → configured Task provider
3. 一筆筆記 → Notion
4. Notion disconnected → Memory still saved
5. Task network failure → Memory still saved
6. Calendar Undo 仍正常
7. Recent 能看出 Cayana 做過的 action

完成後停止。

---

# Stage 10 — Reliability / Privacy / Battery / Final v1 Acceptance

## 目標

不增加新產品功能。

把 Stage 0–9 做到可 release。

---

## 10.1 Reliability

必測：

- reboot
- process death
- permission revoke
- offline
- network fluctuation
- duplicate media
- source deleted
- slow device
- low battery
- WorkManager retry
- concurrent ingest
- DB migration
- Search repair
- Drive backup / restore
- Ask timeout
- Destination auth expiry
- Destination duplicate retry

---

## 10.2 Security / Privacy

檢查：

- Room protection
- Keystore
- Recovery Key
- Drive encryption
- LLM Top-K boundary
- LLM logs
- destination credentials
- OAuth broker
- Calendar boundary
- notification privacy
- crash reports
- analytics

---

## 10.3 Battery

檢查：

- idle wakeups
- background CPU
- photo/audio processing
- embedding rebuild
- WorkManager constraints
- startup time
- memory usage

---

## 10.4 Deferred Final Codex Acceptance

Stage 10 後交由 Codex 做完整系統驗收。

必須包含：

### Stage 6
Real Google Drive E2E：

- auth
- backup
- ciphertext scan
- fresh restore
- wrong key
- Calendar Provider count
- revoke
- Logcat privacy

### v1 全系統
- fresh install
- onboarding
- screenshot → Calendar
- Photo OCR
- Recording STT
- Share
- Search
- Retrieve
- Cayana Ask
- Task destination
- Notion destination
- offline behavior
- process death
- reboot
- battery
- privacy
- release build

---

# 5. v1 最終 E2E

1. 全新安裝。
2. 60 秒 onboarding。
3. Screenshot 一張活動資訊。
4. Event 自動進 Calendar。
5. Notification + Undo 正確。
6. Search 找得到。
7. 原圖刪除後 Memory 還在。
8. Photo OCR 可找。
9. Recording transcript 可找。
10. Shared URL 可找。
11. Google Drive backup。
12. fresh restore。
13. Retrieval 找到正確 Top-K。
14. Cayana Ask 回答正確並附來源。
15. 待辦送到 Task provider。
16. 筆記送到 Notion。
17. 任一 destination 失敗，Memory 仍在。
18. 任一 AI 失敗，Search 仍在。
19. logs 無 Memory plaintext。
20. 背景耗電合理。

全部可靠通過：

> **Cayana v1 MVP PASS**

---

# 6. 每個 Stage 完成回報格式

```text
## Stage
目前 Stage

## Git
branch
commit SHA

## 完成內容
簡短列出

## Tests
command
count
result

## Runtime
實際驗證內容

## Privacy / Security
與本 Stage 相關結果

## 已知問題
沒有就寫無已知阻塞

## Deferred
明確列出不是本 Stage 的項目
```

最後：

> 等待 Cayana 專案驗收後再進入下一 Stage。

---

# 7. 最後提醒

Cayana 的價值不在功能數量。

優先級：

> **Memory 不丟 > 自動化**

> **Privacy > 方便捷徑**

> **Local Retrieval > 把資料丟上雲**

> **Destination failure isolation > 深度整合**

> **可靠性 > 炫技**

> **使用者原本的工具 > 強迫使用 Cayana 生態**

Cayana 的目標：

> **讓使用者照常生活，而需要的資訊之後仍然找得到、問得到、用得到。**
