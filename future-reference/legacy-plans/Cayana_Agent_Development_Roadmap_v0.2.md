# Cayana MVP — Agent 分階段開發任務與驗收標準 v0.2

## 使用方式

這不是「一次把整個 Cayana 做完」的 Master Prompt。

請把本文件當作 **分階段開發協議**。

每次只執行一個 Stage。

完成該 Stage 後：

1. 停止繼續開發下一階段。
2. 確保程式碼已提交到 GitHub。
3. 回報 commit / branch。
4. 回報完成項目。
5. 回報測試結果。
6. 回報已知限制或未解決問題。
7. 等待人工與第二個 AI 驗收後，再取得下一階段指令。

不要自行跨階段實作。

---

# 全域產品邊界

產品名稱：**Cayana**

平台：**Android first**

核心定位：**Personal Memory Layer**

技術方向：

- Kotlin
- Jetpack Compose
- Android native APIs
- Coroutine / Flow
- Local-first
- 模組化、可測試

MVP 包含：

- Screenshots / Photos / Recordings / Shared content ingest
- Local OCR
- Local STT
- Memory Database
- Screenshot → Calendar
- Notifications
- Search
- Google Drive Backup
- Cayana Cloud 基礎能力
- GPT 類 LLM 的 RAG Ask
- AI 額度 / 訂閱 / 點數的基礎 entitlement

MVP 不包含：

- OpenAI Dot 類雲端自主 Agent
- Grok Bot 類自主 Agent
- Agent automation
- Agent 自動執行任務
- Agent 寫入 Memory
- Multi-agent orchestration
- MCP 對外整合
- Knowledge Graph 作為核心依賴
- 社群
- 自製 Camera
- 自製 Recorder
- 完整 Calendar App

未來 Agent 即使加入，也只允許 Read-only Memory access。

---

# 全域工程原則

所有 Stage 都必須遵守：

1. 隱私優先。
2. 不記錄私人 OCR / Transcript 到一般 log。
3. Crash report 不含 Memory 原文。
4. Analytics 不含 Memory 原文。
5. LLM 不直接存取資料庫。
6. LLM 不持有 Calendar credential。
7. 原始圖片 / 錄音預設不上傳 Cayana Server。
8. 背景工作必須盡量低耗電。
9. 不做高頻 polling。
10. 同一來源不可重複 ingest。
11. 原始檔刪除後 Memory 不可跟著消失。
12. 每個 Stage 都必須有測試。
13. 不為未來 Agent 過度設計。
14. 不擅自新增超出 Stage 範圍的大功能。

---

# Stage 0 — 專案基礎與架構骨架

## 目標

建立可以持續開發 Cayana 的 Android 原生專案骨架。

本階段不要做真正的 Screenshot OCR、Calendar、LLM 或 Cloud 功能。

## 要完成

- Android Kotlin 專案
- Jetpack Compose
- 清楚的 package / module 邊界
- dependency injection
- Coroutine / Flow 基礎
- Room 或其他適合的 local database foundation
- repository pattern
- 基礎 navigation
- 最小首頁
- 最小 Settings shell
- logging policy
- unit test framework
- CI 可執行 build + test
- README 說明開發環境與架構

建議預留概念模組：

- source
- processing
- memory
- search
- actions
- backup
- ai
- ui

但不要為了模組化而過度拆分。

## 不要做

- 不接 GPT
- 不做 MCP
- 不做 Cloud Agent
- 不做 Calendar Event creation
- 不做完整 onboarding
- 不做真正 OCR/STT

## 驗收條件

- 專案在乾淨環境可 build
- 測試指令可執行
- CI 通過
- App 可正常啟動
- 首頁與 Settings 可導航
- 核心資料層可透過 interface 替換
- 沒有 God Class 或把所有功能塞進 MainActivity
- README 足以讓另一名工程師接手

完成後停止，提交 GitHub，等待驗收。

---

# Stage 1 — Onboarding、權限與來源設定

## 目標

讓第一次安裝 Cayana 的使用者在 60 秒內完成核心設定。

## 要完成

Onboarding：

1. 歡迎
2. 選擇 Sources
3. 選擇 Calendar
4. Google Drive Backup 可稍後
5. 完成

Sources 至少包含：

- Screenshots
- Camera / Photos
- Recordings
- Downloads

Settings 之後可以重新修改。

Android 權限必須：

- 遵循目前 Android 媒體權限模型
- 不要求與功能無關的權限
- 權限拒絕時不 crash
- 可以重新授權

不要要求：

- 真名
- 電話
- 地址
- 性別
- 年齡
- 不必要 profile

## 驗收條件

- 全新安裝可完成 onboarding
- 正常流程 60 秒內可完成
- 每個 Source 可獨立開關
- 拒絕部分權限後 App 仍可使用其他功能
- 重開 App 後設定保留
- Settings 可以重新修改 Sources / Calendar
- 不存在強制個人資料註冊流程

完成後停止，提交 GitHub，等待驗收。

---

# Stage 2 — Screenshot → OCR → Memory

## 目標

完成第一條真正的 Cayana Memory Pipeline。

使用者在其他 App 截圖後，不需要打開 Cayana，Screenshot 可以被偵測、OCR、建立 Memory 並通知。

## 要完成

- 偵測新的 Screenshot
- 避免高頻掃描
- 去重
- Local OCR
- 支援繁體中文與英文
- 建立 Memory Item
- 保存 OCR text
- 保存 source URI / timestamp
- 建立 processing state
- 發出簡短 Notification
- Recent Activity 顯示該 Memory
- 原始 Screenshot 刪除後，Memory 仍存在

此階段不做 Calendar。

## 驗收條件

- 在其他 App 截圖後可自動建立一筆 Memory
- 同一 Screenshot 不會重複建立
- OCR 可正確處理常見繁中 + 英文文字
- Cayana 不需要一直開在前景
- 通知簡短，不出現冗長 AI 說明
- 刪除原始 Screenshot 後，OCR / Memory 不消失
- App process 被殺掉再啟動，不會重複 ingest 舊 Screenshot
- 有單元 / 整合測試覆蓋 dedup 與 Memory persistence

完成後停止，提交 GitHub，等待驗收。

---

# Stage 3 — Screenshot → Calendar

## 目標

完成 Cayana MVP 最重要的 Killer Feature。

使用者看到日期與活動內容，只需 Screenshot，就能自動加入 Calendar。

## 要完成

建立 EventCandidate pipeline：

- Date parser
- Time parser
- 基礎 title extraction
- 基礎 location extraction
- confidence policy

行為：

### 高信心

自動建立 Calendar Event。

通知：

> 已加入行事曆  
> [事件] · [日期時間]  
> 復原

### 中信心

通知詢問：

> 發現可能的行程  
> 加入 / 忽略

### 低信心

只保存 Memory。

必須避免：

- 過去日期誤建立
- 營業時間誤建立
- 多個互相衝突日期直接建立
- 同一 Screenshot 重複建立 Event

Undo 必須真的刪除剛建立的 Event。

## 驗收條件

使用測試文字：

> 10/18 19:30  
> XX Live  
> 台北流行音樂中心

完成 Screenshot 後：

- 不打開 Cayana
- Event 自動建立
- 日期正確
- 時間正確
- Title 合理
- 地點可保存
- Notification 正確
- Undo 可成功刪除 Event
- 同一張 Screenshot 不會產生第二個 Event

另外需測試：

- 營業時間
- 折扣截止日
- 過去日期
- 多日期
- 日期模糊
- OCR 錯字

Calendar false positive 必須明顯受到控制。

完成後停止，提交 GitHub，等待驗收。

---

# Stage 4 — Photos、Recordings 與背景處理

## 目標

把 Cayana 從 Screenshot 工具擴展成 Personal Memory Layer。

## 要完成

### Photos

對使用者授權來源：

- 新照片偵測
- OCR
- metadata
- timestamp
- 可取得的 location metadata
- Memory Item

不用在此階段做大型 Vision LLM。

### Recordings

- 偵測新 audio
- Local STT abstraction
- 本機語音轉文字
- 長錄音 chunk processing
- progress persistence
- process death 後可恢復
- transcript 寫入 Memory
- 低電量時避免重工作
- 適合時利用 WorkManager constraints

## 驗收條件

- 含文字照片可進入 Memory 並被搜尋
- Audio 可產生 transcript
- 長錄音不造成 UI 卡頓
- 中途中止後可繼續處理
- 不把原始 audio 預設上傳 Cayana server
- 原始 Photo / Recording 刪除後，Memory 仍存在
- 背景處理沒有高頻 polling

完成後停止，提交 GitHub，等待驗收。

---

# Stage 5 — Share Sheet、統一搜尋與 Recent Activity

## 目標

完成日常資訊進入 Cayana 的第二個主要入口，以及可靠的本機找回能力。

## 要完成

Android Share Target：

- plain text
- URL
- image
- file

URL 最少保存：

- originalUrl
- capturedAt
- source type

能取得 metadata 時保存：

- title
- description
- canonical URL
- source metadata

解析失敗仍必須建立 Memory。

Search：

- OCR
- Transcript
- Title
- URL
- Date
- Source type
- metadata / entity

Recent Activity：

- source
- time
- title / extraction
- action history
- Calendar action state

Memory Detail：

- structured content
- source
- captured date
- action history
- original source still available / unavailable

## 驗收條件

- 從瀏覽器 Share URL 可建立 Memory
- URL metadata 抓不到時仍可保存
- Screenshot、Photo、Audio、URL 都可透過同一搜尋入口找到
- 搜尋不依賴 LLM
- 原始 URL 可以返回
- Recent Activity 能回答「Cayana 剛剛做了什麼」

完成後停止，提交 GitHub，等待驗收。

---

# Stage 6 — Google Drive 加密備份與還原

## 目標

確保手機遺失、App 清除或換機時，Memory 不會消失。

## 要完成

- Google Drive backup
- App 專用資料區
- Client-side encryption
- Backup Manifest
- schema version
- checksum / integrity
- Backup now
- Last backup status
- Restore
- restore 後 rebuild search index

備份 canonical data：

- Memory
- OCR
- Transcript
- Entities
- Action history
- 必要 settings

不備份可重建：

- cache
- temp
- rebuildable search index
- rebuildable embeddings

## 驗收條件

- 可以產生加密 backup
- Drive 上不是明文 Memory
- 清空本機 DB 後可完整 restore
- restore 後可以搜尋
- Calendar action history 不遺失
- corrupted / incomplete backup 有合理錯誤處理
- 不會因 backup 失敗破壞本機資料

完成後停止，提交 GitHub，等待驗收。

---

# Stage 7 — Cayana Cloud 基礎同步與濫用限制

## 目標

建立有限雲端 Memory 能力，支援跨裝置與之後的 RAG，但不要變成大型媒體雲端硬碟。

## 要完成

Cloud 只保存必要資料：

- structured Memory
- OCR / Transcript
- metadata
- necessary indexes

原始大型 media 預設不上傳。

需要：

- anonymous/internal account identity
- tenant isolation
- encrypted transport
- at-rest encryption
- storage quota
- rate limit
- abuse prevention
- sync conflict 基本策略

不要要求不必要個人資料。

## 驗收條件

- A 使用者不能讀到 B 使用者 Memory
- client 改寫 user ID 不能越權
- Search / API / queue 都維持 tenant isolation
- 免費 quota 可正常阻擋明顯濫用
- 正常日常使用不容易撞 quota
- 雲端中沒有原始 Screenshot / Audio，除非未來有明確 opt-in 功能

完成後停止，提交 GitHub，等待驗收。

---

# Stage 8 — GPT 類 LLM RAG Ask

## 目標

讓使用者可以直接在 Cayana 問自己的 Memory。

這是 MVP 範圍。

這不是雲端自主 Agent。

## 要完成

Ask UI：

> 想找什麼？

流程：

```text
Question
→ Retrieval Layer
→ Top-K Memories
→ LLM
→ Answer + Memory Sources
```

建立 LLM provider abstraction。

第一版可以先串接一個 GPT 類模型，但架構不能讓 UI / Database 綁死特定供應商。

LLM 不得：

- 直接 SQL
- 選 tenant
- 讀全部 Memory
- 取得 Drive key
- 取得 Calendar credential
- 寫入 / 修改 / 刪除 Memory
- 自主執行其他工作

## 驗收條件

建立 Memory：

> XX Live，10/18 19:30，台北流行音樂中心

詢問：

> 我十月是不是有一場演唱會？

必須：

- Retrieval 找到正確 Memory
- LLM 只取得必要 Top-K
- 回答內容符合 Memory
- 顯示來源 Memory
- 無 relevant Memory 時不捏造
- 嘗試 prompt injection 要求「讀其他使用者資料」無法越權
- LLM request logs 不包含不必要的大量私人內容

完成後停止，提交 GitHub，等待驗收。

---

# Stage 9 — AI 額度、訂閱與點數

## 目標

讓內建 LLM Ask 的成本可控，但不破壞免費核心產品。

## 要完成

- Free AI allowance
- Subscription entitlement
- Consumable AI credits
- AI usage meter
- 清楚但低調的方案頁
- 額度用完後 fallback 到本機 Search

核心本機功能永遠不因 AI 額度用完而停用。

不要：

- 強迫升級
- popup spam
- 紅色倒數
- 每日登入任務
- 限制 Screenshot → Calendar
- 限制 OCR / STT / Search / Backup 作為付費牆

## 驗收條件

- 免費 AI 額度可正常扣除
- 訂閱 / 點數 entitlement 判斷可靠
- 額度為零時本機搜尋仍完整可用
- AI 成本無法透過簡單重送或併發繞過 quota
- UI 保持低調

完成後停止，提交 GitHub，等待驗收。

---

# Stage 10 — Reliability、Security、Battery 與 MVP Release

## 目標

不是新增功能，而是把前面所有流程做到可長期實機使用。

## 必須處理

- reboot
- app process death
- permission revoked
- duplicate media
- source deleted
- offline
- slow device
- battery low
- Calendar write failure
- Drive backup failure
- Cloud sync failure
- transcription interrupted
- corrupted job state
- database migration
- notification privacy
- race condition
- concurrent processing

Security review：

- local DB protection
- Keystore
- tenant isolation
- logs
- crash reports
- analytics
- cloud access control
- RAG boundary
- Calendar boundary
- backup encryption

Performance：

- background CPU
- wakeups
- battery
- memory
- startup time

## MVP 最終驗收

完整 E2E：

1. 全新安裝
2. 60 秒內 onboarding
3. Screenshot 一個活動
4. 不開 Cayana
5. 自動加入 Calendar
6. Notification 正確
7. 搜尋可找到
8. 刪除 Screenshot
9. Memory 仍存在
10. Audio 可產生 transcript
11. Share URL 可保存
12. Google Drive backup 成功
13. 清空本機資料
14. Restore 成功
15. GPT Ask 能回答已恢復的 Memory
16. 來源可追溯
17. 無 cross-user leakage
18. 背景耗電在合理範圍

只有這一整條可靠通過，才視為 Cayana MVP 完成。

---

# 每個 Stage 完成後的 Agent 回報格式

每次完成 Stage 後，只回報：

## Stage
目前完成的 Stage。

## Git
- branch
- commit hash

## 完成內容
簡短列出。

## 測試
- 執行的測試
- 通過數
- build 狀態

## 實機驗證
有做哪些實機流程。

## 已知問題
沒有就寫「無已知阻塞問題」。

## 下一階段
不要自行開始。

明確寫：

> 等待 Cayana 專案驗收後再進入下一 Stage。

---

# 與第二次 AI 驗收的協作方式

開發 Agent 完成一個 Stage → push GitHub。

接著專案負責人把：

- repository
- commit hash
- Agent 報告

交給另一個 AI 做二次驗收。

二次驗收重點：

1. 是否真的符合當階段 Acceptance Criteria
2. 是否偷偷跨階段加入不必要架構
3. 是否有安全 / 隱私問題
4. 是否存在 duplicate / race / lifecycle 問題
5. 是否真的跑過測試，而不是只聲稱通過
6. 是否需要補測試
7. 是否適合批准進入下一 Stage

未通過：

回原 Agent 修正目前 Stage。

通過：

才下發下一 Stage。

---

# 最後提醒

Cayana 的價值不在功能數量。

第一優先永遠是：

> **使用者照常生活，Cayana 在背後安靜地幫他記住。**

可靠性 > 功能數量  
隱私 > 便利捷徑  
Calendar 正確率 > 自動化率  
低耗電 > 即時炫技  
簡單 > 過度設計
