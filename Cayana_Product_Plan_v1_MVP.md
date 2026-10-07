# Cayana — Personal Memory Layer
## v1 MVP 產品企劃書

> **你只管生活。Cayana 幫你記得。**

---

# 1. 產品定義

Cayana 是一個 **Personal Memory Layer（個人記憶層）**。

它不是新的筆記 App、Calendar、Tasks、雲端硬碟、會員生態系或自主 Agent。

Cayana 的工作是：

> **把使用者日常生活中自然產生的資訊碎片收進來，在本機理解、整理、找回，並在適合時把它送到使用者真正想使用的工具。**

v1 的核心可以濃縮成四個動詞：

1. **Capture** — 記住
2. **Retrieve** — 找回
3. **Ask** — 詢問
4. **Act** — 行動

---

# 2. 核心產品哲學

## 2.1 不改變使用者原本的生活方式

使用者繼續：

- 用系統 Screenshot
- 用原本的 Camera
- 用原本的 Recorder
- 用原本的 Browser / Instagram / YouTube / Maps
- 用原本的 Calendar
- 用原本的 Tasks / Todo App
- 用原本的 Notes / Notion

Cayana 不要求：

> 打開 Cayana → 建立資料 → 分類 → 儲存

而是：

> 日常行為發生 → Cayana 在背後理解 → Memory 被留下 → 必要時採取動作

理想情況下，使用者設定完成後可以長時間不主動打開 Cayana。

---

# 3. Cayana 不是新的資料生態系

v1 不建立 Cayana Cloud Memory，也不建立為了同步 Memory 而存在的 Cayana 帳號系統。

Cayana v1 的 canonical source of truth 是：

> **裝置上的 Local Memory Database**

Google Drive 的角色是：

> **加密備份與災難復原**

不是：

> Cayana 的主要線上資料庫。

外部服務的角色是：

> **Destination**

而不是：

> Cayana 的必要依賴。

因此：

- 沒有 Notion，Cayana 一樣可用。
- 沒有 Todoist，Cayana 一樣可用。
- Calendar 權限被拒絕，Memory 仍然存在。
- AI 解析失敗，Memory 仍然存在。
- LLM 暫時不可用，本機搜尋仍然可用。

核心規則：

> **什麼都能存；能理解多少就理解多少；永遠不因 AI、網路或外部服務失敗而拒絕收藏。**

---

# 4. Capture — 記住

Cayana v1 的 Memory 來源：

- Screenshot
- Photo
- Recording
- Shared Text
- Shared URL
- Shared Image
- Shared File / PDF
- Downloads（使用者授權時）

所有來源都轉成統一的 **Memory Item**。

概念：

```json
{
  "id": "uuid",
  "sourceType": "screenshot",
  "createdAt": "...",
  "capturedAt": "...",
  "title": "...",
  "rawText": "...",
  "normalizedText": "...",
  "sourceUri": "...",
  "sourceUrl": "...",
  "sourceExists": true,
  "metadata": {},
  "entities": [],
  "eventCandidates": [],
  "processingState": "complete"
}
```

---

# 5. Index, don't duplicate

Cayana 不把自己變成另一套媒體雲端硬碟。

原始 Screenshot、Photo、Recording、PDF 等大型內容原則上仍留在原始來源。

Cayana 保存：

- OCR
- Transcript
- Metadata
- Entities
- 日期與時間
- 地點資訊
- URL
- Search index
- 必要 embedding
- Action history
- 來源 reference

如果原始媒體日後被刪除：

```text
sourceExists = false
```

但：

> **Memory 不跟著消失。**

---

# 6. Local-first

優先在手機本機完成：

- OCR
- Speech-to-Text
- metadata extraction
- deterministic date/time parsing
- entity extraction
- full-text search
- lightweight semantic retrieval
- query routing
- Top-K context selection
- background ingestion

v1 不把完整 Memory Database 上傳到 Cayana Server。

---

# 7. Retrieve — 找回

搜尋必須在沒有 LLM 的情況下就能成立。

第一層：

- FTS
- OCR
- Transcript
- Title
- URL
- Date
- Source type
- metadata
- entity

第二層可以使用本機輕量 retrieval：

- small embedding model
- semantic similarity
- reranker
- deterministic filters

目標不是在手機上跑大型聊天模型。

本機 retrieval 的任務只有：

> **從大量 Memory 中找出這次問題真正需要的少數幾筆。**

典型流程：

```text
Question
→ Query normalization
→ FTS / filters / semantic retrieval
→ Candidate Memories
→ Local rerank
→ Top-K
```

---

# 8. Ask — 問自己的記憶

Cayana v1 提供自己的 **Cayana Ask**。

流程：

```text
使用者問題
↓
Local Retrieval
↓
Top-K authorized Memories
↓
Cayana LLM API
↓
Answer + Sources
```

重要：

> **LLM 永遠不是 Database user。**

Cayana Server 不取得：

- 整個 Memory Database
- Room database access
- Drive Recovery Key
- Calendar credential
- 任意 tenant selector
- 本機媒體存取權

LLM 只收到：

> **當次問題 + Cayana 本機已經選出的最少必要 Top-K context**

---

# 9. Cayana LLM Server 的角色

v1 的 Cayana Server 是：

> **推理服務**

不是：

> **Memory Cloud**

Server 可以處理：

- 問題
- Top-K context
- answer generation
- usage / rate limiting
- model routing
- abuse prevention

Server 不應長期保存使用者完整 Memory。

正常 request 完成後，不需要保留 context 內容。

任何 telemetry / logs：

不得記錄：

- OCR 原文
- Transcript 原文
- 私人 URL
- Top-K Memory 全文
- 使用者問題全文（除非未來有明確 opt-in 診斷模式）

---

# 10. Cayana Ask 不要求傳統會員帳號

v1 不需要因為 AI 問答而建立：

- email account
- phone account
- username/password
- profile system

可以使用匿名 installation credential / opaque client identifier 做：

- 基本 rate limit
- 免費 Ask quota
- abuse protection

如果未來要加入付費、跨裝置 entitlement 或其他產品帳號：

> **那是獨立的 account / commerce capability，不應反過來變成 Cayana Memory 的必要條件。**

---

# 11. 未來外部 LLM

未來 ChatGPT、Claude、Gemini 或其他 LLM 可以透過 Cayana connector / capability 使用使用者的記憶。

原則仍然一樣：

```text
External LLM
→ Cayana query request
→ User device
→ Local Retrieval
→ Top-K
→ External LLM
```

外部 LLM 不應取得整個 Cayana Database。

v1 不必完成這項外部 connector。

但 Retrieval Layer 應保持 provider-independent，避免只為 Cayana LLM 寫死。

---

# 12. Act — 把資訊送到正確工具

Cayana 不自己重做所有 productivity app。

它應該成為：

> **理解層 + Action Router**

例如：

```text
行程 / 活動
→ Calendar

待辦 / Deadline
→ Tasks / Todo Provider

筆記 / 知識 / 收藏
→ Notion

無法確定
→ 只保留 Cayana Memory
```

Destination 失敗：

> **不能造成 Memory 保存失敗。**

---

# 13. Destination Neutrality

Cayana 應對目的地保持中立。

例如待辦：

- Google Tasks
- Microsoft To Do
- Todoist
- 未來其他 Task Provider

筆記：

- Notion
- Android Share Sheet
- 未來其他 Notes Provider
- 未來我們自己的產品

即使未來我們自己開發 Calendar、Tasks、Knowledge App：

> 對 Cayana 而言也只是另一個 Destination。

不要讓自家產品取得必須使用的特殊地位。

---

# 14. Screenshot → Calendar

這是 Cayana v1 已完成且最具代表性的 Killer Feature。

使用者看到：

> 10/18 19:30  
> XX Live  
> 台北流行音樂中心

只需要 Screenshot。

Cayana：

1. 偵測 Screenshot
2. Local OCR
3. 解析 Event Candidate
4. deterministic date/time extraction
5. confidence policy
6. 高信心時寫入 Calendar
7. Notification
8. Undo

高信心：

→ 自動加入 Calendar。

中信心：

→ Notification 詢問加入 / 忽略。

低信心：

→ 只保存 Memory。

原則：

> **Calendar false positive 的成本高於 false negative。**

---

# 15. Tasks Destination

v1 應提供待辦 Action 能力。

典型內容：

> 明天下午記得去拿包裹

Cayana 應能理解：

```text
type = TASK
title = 拿包裹
due = tomorrow afternoon
```

再依使用者設定：

> Tasks Destination → Google Tasks / Todoist / Microsoft To Do / 其他支援項目

v1 至少完成一個正式可用 Task provider，架構需保留 provider abstraction。

如果沒有設定 Task provider：

> 仍然只建立 Memory，不失敗。

---

# 16. Notion Destination

Notion 在 Cayana v1 的定位：

> **結構化筆記／知識 Destination**

不是：

> Cayana 的主資料庫。

使用者可以選擇：

- 關閉
- 每次詢問
- 對特定類型自動送出

例如：

```text
研究資料 → Notion
旅遊收藏 → Notion
私人聊天截圖 → Local only
```

Notion 連線失敗：

> Memory 仍保存。

OAuth secret 不可放入 Android APK。

如果 Notion OAuth 需要 server-side token exchange：

> 使用最小化 OAuth Broker。

Broker 不需要保存完整 Cayana Memory。

---

# 17. Google Drive Backup

Google Drive Backup 仍是 v1 必要功能。

用途：

> 手機遺失、換機、清除 App Data 時復原 Local Memory。

流程：

```text
Local canonical Memory
→ logical snapshot
→ client-side encryption
→ Google Drive appDataFolder
```

備份：

- canonical Memory
- OCR
- Transcript
- Entities
- action history
- portable settings

不備份：

- cache
- FTS
- rebuildable embedding
- temp
- model files
- tokens
- Recovery Key

Recovery Key 本身不上傳。

---

# 18. Privacy Boundary

Cayana 可能處理：

- 私人聊天
- 地址
- 行程
- 工作內容
- 錄音逐字稿
- 個人照片中的文字
- 網頁與搜尋資訊

因此：

- 不把完整 Memory 上傳 Cayana Cloud
- 原始媒體預設不上傳 Cayana Server
- LLM 只看 Top-K
- 一般 log 不含 Memory plaintext
- crash report 不含 Memory plaintext
- analytics 不含 Memory plaintext
- Recovery Key 不上傳
- Calendar credential 不交給 LLM
- Destination token 必須最小權限、安全儲存

---

# 19. Notification 是主要 UI

普通內容：

> **已記住**

商品：

> **已記住**  
> RTX 5090 · NT$72,900

Calendar：

> **已加入行事曆**  
> XX Live · 10/18 19:30  
> `復原`

Task：

> **已加入待辦**  
> 拿包裹 · 明天下午  
> `復原`（若 provider 支援）

不確定：

> **發現可能的行程**  
> `加入` `忽略`

不要：

> 「AI 已完成智慧分析」

---

# 20. UI

Cayana 保持安靜。

Home：

```text
Cayana                              ⚙

             想找什麼？

      [ 問你的記憶…… ]

最近

10:42   XX Live
        已加入行事曆 ✓

昨天    RTX 5090
        Screenshot

昨天    台南牛肉湯
        Shared URL
```

Recent 同時是 Audit Trail。

---

# 21. Background Architecture

正常流程：

```text
Source event
→ queue
→ local processing
→ persist Memory
→ optional action routing
→ notification
→ idle
```

目標：

> **99% 時間幾乎不消耗 CPU。**

禁止：

- 高頻 polling
- 常駐大型 LLM
- 持續掃描整個 filesystem
- 不必要的常駐 socket
- 因 AI 或外部服務不可用而阻塞 ingestion

---

# 22. v1 MVP 包含

- Android native app
- onboarding
- Screenshot ingest
- Photo ingest
- Recording ingest
- Share Sheet
- Local OCR
- Local STT
- unified Memory model
- Search
- Recent / Detail
- Screenshot → Calendar
- Google Drive encrypted backup / restore
- Local retrieval layer
- Cayana Ask
- Cayana LLM server inference
- Action Router
- 至少一個 Tasks destination
- Notion destination
- privacy / reliability / battery hardening

---

# 23. v1 MVP 不包含

- Cayana Cloud Memory sync
- Supabase Memory backend
- Cayana 多裝置同步
- 傳統 Cayana account ecosystem
- email / phone membership
- 自製 Calendar App
- 自製 Tasks App
- 自製 Notion 替代品
- JSON-like 第二大腦新產品
- Agent automation
- Agent 寫入 / 修改 / 刪除 Memory
- Multi-agent orchestration
- 外部 LLM connector / MCP 正式產品化
- Knowledge Graph 作為必要架構
- 大型原始 media cloud
- subscription / credits / billing 作為 MVP 必要條件

這些內容可以保留為：

> **Post-v1 / Future Reference**

但不能干擾 v1 production source。

---

# 24. v1 成功標準

完整使用場景：

1. 全新安裝
2. 60 秒內完成 onboarding
3. Screenshot 一張活動資訊
4. 不打開 Cayana
5. Event 正確加入 Calendar
6. Notification + Undo 正確
7. Screenshot 可從本機 Search 找回
8. 原始 Screenshot 刪除後 Memory 仍存在
9. Photo OCR 可搜尋
10. Recording transcript 可搜尋
11. Shared URL 可保存
12. Google Drive encrypted backup 成功
13. fresh restore 後 Memory 回來
14. 問：
   > 我十月是不是有一場演唱會？
15. Local Retrieval 找出正確 Top-K
16. 只有 Top-K 被送到 Cayana LLM
17. 回答正確且附來源
18. 一筆待辦能送往使用者選擇的 Tasks destination
19. 一筆筆記能送往 Notion
20. 任一外部服務失敗時，本機 Memory 仍完整
21. 背景耗電合理
22. Logs / crash / analytics 無 Memory plaintext

這條路徑可靠通過：

> **Cayana v1 MVP 完成。**

---

# 25. 最終產品定義

Camera 負責看。

Recorder 負責聽。

Calendar 負責時間。

Tasks 負責待辦。

Notion 負責知識整理。

LLM 負責推理與回答。

而 Cayana：

> **負責在資訊出現的那一刻把它記住、找回、理解，並在需要時送到正確的地方。**

Cayana 不需要擁有所有資料與工具。

它只需要成為：

> **最可靠、最安靜的 Personal Memory Layer。**
