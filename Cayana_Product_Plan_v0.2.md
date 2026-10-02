# Cayana — Personal Memory Layer
## 產品企劃書 v0.2

> **核心一句話：你只管生活，Cayana 幫你記得。**

---

## 1. 產品定位

**Cayana** 取自梵文 *cayana*，帶有「收集、聚攏、拾起」的意象。

Cayana 不是筆記軟體、相簿、錄音 App，也不是一個要求使用者改變習慣的 AI 助手。

它是一層安靜存在於手機背後的 **Personal Memory Layer（個人記憶層）**：

- 使用者照常截圖。
- 照常使用系統相機拍照。
- 照常使用系統錄音機錄音。
- 照常在 Instagram、YouTube、Google Maps、瀏覽器裡分享網址。
- 照常使用 Google Calendar 或系統行事曆。
- 需要回想時，再透過 Cayana 或連結的 LLM 問自己的記憶。

Cayana 的工作只有一件事：

> **把日常生活中散落的資訊碎片拾起來，轉換成之後找得回來、能被搜尋與理解的個人記憶。**

---

## 2. 使用者問題

現代人的重要資訊大量散落在：

- Screenshots
- Camera Photos
- Recordings
- Reels
- Shorts
- Google Maps
- 網頁
- PDF
- 聊天截圖
- 社群貼文
- Downloads

問題通常不是「沒有保存」，而是：

> **保存了，之後卻再也找不到。**

尤其 Screenshot 已經成為很多人的臨時記憶：

- 看到活動 → 截圖
- 看到日期 → 截圖
- 看到商品 → 截圖
- 看到餐廳 → 截圖
- 朋友傳集合時間 → 截圖

最後卻只得到數千張沒有結構的圖片。

Cayana 不要求使用者建立分類、標籤或新的筆記習慣，而是直接理解這些既有行為。

---

## 3. 核心產品原則

### 3.1 不改變使用者習慣

不要設計成：

> 打開 Cayana → 新增 → 拍照 → 選分類 → 儲存

而應該是：

> 截圖 / 拍照 / 錄音 / 分享 → Cayana 自動處理

理想情況下，使用者設定完 Cayana 後可以長時間不再打開 App。

---

### 3.2 Index, don't duplicate

Cayana 不以重新保存大型原始媒體為主要策略。

例如：

- 4 MB Screenshot
- 10 MB Photo
- 200 MB Recording
- Video

原始檔繼續存在使用者的手機或原始來源。

Cayana 優先保存：

- OCR 文字
- Transcript
- Metadata
- Entities
- 日期 / 時間
- 地點
- Source information
- Search index
- 必要的 embedding
- Action history
- 摘要或結構化 JSON

因此，即使使用者日後刪掉手機裡的原始 Screenshot、Photo 或 Recording：

> **已建立的 Memory 仍然存在。**

---

### 3.3 Local-first，但不是 Local-only

Cayana 應優先使用本機能力完成：

- OCR
- Speech-to-Text
- metadata extraction
- 基礎日期辨識
- 本機全文搜尋
- 背景 ingest

但產品可以提供有限的 Cayana Cloud：

- 儲存結構化 Memory
- 跨裝置同步基礎資料
- RAG 所需索引或必要資料
- 防止單一裝置遺失
- 免費使用者有合理容量與 rate limit

由於保存的主要是文字與結構化資料，正常使用者的空間成本應非常低。

免費容量上限主要是為了：

> **防止惡意濫用，而不是逼正常使用者付費。**

---

## 4. MVP 範圍

MVP 應包含以下能力：

1. Android 原生 App
2. 一分鐘內完成 onboarding
3. 監看使用者授權的 Screenshot / Photo / Recording / Download 來源
4. 本機 OCR
5. 本機語音轉文字
6. 統一 Memory 資料模型
7. Screenshot → Calendar
8. Notification / Undo / Confirm
9. Android Share Sheet
10. 本機搜尋
11. Recent Activity / Audit Trail
12. Google Drive 加密備份與還原
13. 有限 Cayana Cloud Memory
14. GPT 類 LLM 的 RAG 問答
15. 基本訂閱 / 點數能力，用於額外內建 LLM 問答

MVP **不包含**：

- OpenAI Dot 類常駐雲端 Agent
- Grok Bot 類自主代理
- Agent automation
- Agent 自主執行工作
- Agent 寫入 / 修改 / 刪除 Memory
- Multi-agent orchestration
- Agent workflow builder
- Knowledge Graph 作為必要架構
- 社群功能
- 自製 Camera
- 自製 Recorder
- 完整 Calendar App

未來即使支援 MCP 或外部 Agent，也只考慮 **Read-only Memory access**。

---

## 5. Killer Feature：Screenshot → Calendar

這是 Cayana MVP 最重要的體驗，也是最適合對外宣傳的能力。

例如使用者看到：

> 10/18 晚上 7:30  
> XX Live  
> 台北流行音樂中心

使用者只需要：

> **按一次系統 Screenshot。**

Cayana 自動：

1. 偵測新的 Screenshot
2. 本機 OCR
3. 抽取 Event Candidate
4. 解析日期、時間、標題、地點
5. 判斷 confidence
6. 高信心時自動建立 Calendar Event
7. 發出簡短通知
8. 提供 Undo

通知：

> **已加入行事曆**  
> XX Live · 10/18 19:30  
> `復原`

使用者不需要打開 Cayana。

---

## 6. Event Safety

Calendar 建立不能單純相信 LLM。

優先使用：

- deterministic parser
- date/time parser
- heuristic rules
- confidence policy

### 高信心

只有一個明確未來事件：

- 日期明確
- 時間明確
- 標題可推定
- 無互相衝突日期

→ 自動加入。

### 中信心

有合理事件，但存在歧義。

→ Notification 詢問：

> **發現可能的行程**  
> [加入] [忽略]

### 低信心

例如：

- 營業時間
- 特價截止日期
- 多個事件
- 過去日期
- 無法判定使用者意圖

→ 只建立 Memory，不修改 Calendar。

原則：

> **Calendar 的 false positive 成本高於 false negative。**

---

## 7. Notification 是主要 UI

Cayana 大多數時間不應該被使用者主動打開。

所以 Notification 本身就是核心 UI。

普通內容：

> **已記住**

辨識到商品：

> **已記住**  
> RTX 5090 · NT$72,900

成功建立活動：

> **已加入行事曆**  
> XX Live · 10/18 19:30  
> `復原`

事件不確定：

> **發現可能的行程**  
> XX Live · 10/18  
> `加入` `忽略`

不要使用：

> 「AI 已完成智慧分析」

Cayana 的語氣應安靜、簡短。

---

## 8. Screenshot / Photo

使用者不需要 Cayana Camera。

Cayana 讀取使用者授權的來源。

主要處理：

- OCR
- 建立時間
- EXIF / metadata
- 可取得的 location metadata
- 基礎 entity extraction

MVP 不要求大型 Vision Model 理解所有沒有文字的照片。

第一版應先把：

> **含文字的 Screenshot 與 Photo**

做到可靠。

---

## 9. Recording

Cayana 不提供錄音功能。

使用者繼續使用：

- 系統 Recorder
- Google Recorder
- Samsung Recorder
- 其他錄音工具

Cayana 在使用者授權後讀取錄音來源。

流程：

Audio  
→ Local Speech-to-Text  
→ Transcript  
→ Entity extraction  
→ Search index  
→ Memory

預設應以 Local ASR 為主。

可評估：

- whisper.cpp
- sherpa-onnx
- 其他 Android 可離線模型

長錄音：

- 分段處理
- 可續傳 / 可恢復
- 避免 UI 卡頓
- 可在充電 / 電量充足時處理

---

## 10. Share Sheet

Cayana 必須出現在 Android 系統 Share Sheet。

接受：

- Text
- URL
- Image
- File

典型來源：

- Instagram Reels
- YouTube Shorts
- Google Maps
- GitHub
- 一般網頁
- PDF

URL 最少必須保留：

- originalUrl
- source type
- title（若可取得）
- capturedAt

核心規則：

> **解析失敗 ≠ 保存失敗。**

即使拿不到 transcript、caption 或 metadata，也必須保留原始網址。

---

## 11. 統一 Memory Model

Screenshot、Photo、Audio、URL 都不應該變成四套互不相容的資料庫。

全部應轉換成統一的 Memory Item。

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

原始媒體被刪除時：

```text
sourceExists = false
```

但 Memory Item 不刪除。

---

## 12. Search

第一版至少支援：

- 全文搜尋
- OCR
- Transcript
- Title
- 日期
- Source type
- 基礎 entity
- metadata

範例：

- `5090`
- `十月演唱會`
- `台南牛肉湯`
- `上次錄音裡提到 Ashraya`
- `我之前截圖的那個捷克房子`

Search 本身不應依賴 LLM。

---

## 13. GPT / LLM RAG Ask

MVP 包含 GPT 類 LLM 的問答。

流程必須是：

User Question  
→ Cayana Retrieval  
→ Top-K Memories  
→ LLM  
→ Answer + Sources

LLM 不可以直接存取 Database。

LLM 不可以：

- 自行下 SQL
- 指定其他 user / tenant
- 掃描所有 Memory
- 取得 encryption key
- 直接持有 Calendar credential
- 修改 Memory

它只能看到：

> **由 Cayana Retrieval Layer 授權後提供的當次相關 context。**

---

## 14. LLM 商業模式

Cayana 的核心功能免費。

內建 AI 問答只是一個便利入口。

免費版可提供：

- 少量內建 LLM 問答額度
- 完整本機搜尋
- 完整 Memory ingest
- Screenshot → Calendar
- OCR / STT
- Drive Backup
- 基本 Cloud Memory

需要更多內建 AI 問答的使用者可以：

- 訂閱
- 購買點數

設計理念：

> **使用者為額外模型使用成本付費，而不是為解鎖 Cayana 的核心能力付費。**

不要 aggressive paywall。

不要每日登入獎勵。

不要紅色升級 banner。

---

## 15. Privacy & Security

Cayana 儲存的內容可能包含：

- 私人聊天
- 地址
- 行程
- 錄音逐字稿
- 工作文件
- 個人照片文字
- 搜尋資訊

所以安全必須是架構的一部分。

### 最小身分

Cayana 不需要知道：

- 真名
- 性別
- 年齡
- 地址
- 電話

只需要知道：

> 哪些 Memory 屬於哪個匿名 account / device。

---

### Data Isolation

每筆 Cloud Memory 都必須有伺服器驗證過的 tenant/account context。

不可相信 client 自行指定 user ID。

LLM 不能直接指定 tenant。

所有：

- Database
- Cache
- Queue
- Search
- Cloud retrieval

都必須維持相同的 tenant isolation。

---

### Encryption

- TLS in transit
- Local database encryption
- Android Keystore 保護本機 key
- Cloud data at-rest encryption
- Google Drive backup client-side encryption
- 不在 log / crash report / analytics 記錄 Memory 原文

---

## 16. Calendar Integration

Android MVP 優先透過 Android Calendar Provider。

Onboarding：

> **遇到行程時要加入哪一本日曆？**

列出可寫入 Calendar。

如果 Google Calendar 已同步至 Android，即可選取對應 calendar。

這能避免 MVP 額外維護不必要的 Calendar backend。

LLM 不直接操作 Calendar。

Validated Event Candidate 才能交給 CalendarWriter。

---

## 17. Google Drive Backup

Google Drive Backup 是 MVP 必要能力。

目的：

> 手機遺失、換機或清除 App 後，Personal Memory 仍能恢復。

流程：

Local canonical Memory  
→ Backup Snapshot  
→ Client-side Encryption  
→ Google Drive App Data

備份：

- Memory records
- OCR
- transcripts
- entities
- action history
- 必要 settings

不備份可重新產生：

- cache
- temp files
- rebuildable search indexes
- rebuildable embeddings

需要：

- Backup now
- Automatic backup
- Last backup status
- Restore
- Backup versioning
- integrity checksum

---

## 18. Cayana Cloud

Cayana 可以提供有限免費 Cloud Memory。

保存的核心是：

- structured text
- metadata
- encrypted user memory
- 必要索引

而不是大量原始 media。

免費使用者應有：

- 合理空間
- 單日 ingest 上限
- API rate limit
- abuse detection

正常使用者應很難碰到限制。

Cloud quota 是防濫用，不是付費牆。

---

## 19. Onboarding

目標：

> **60 秒以內完成。**

### Step 1

**Cayana**

把你看過的東西，  
變成找得回來的記憶。

`開始`

### Step 2

**讓 Cayana 記住什麼？**

- Screenshots
- Camera
- Recordings
- Downloads

### Step 3

**遇到行程時放去哪裡？**

選擇 Calendar。

### Step 4

**備份你的 Memory**

Google Drive  
`連結`

可以稍後設定。

### Step 5

**完成**

> 現在可以關掉 Cayana 了。

不要：

- 強制註冊個人資料
- 問姓名
- 十頁介紹
- personality setup
- 教學 carousel

---

## 20. App UI

Cayana 不需要大量 Tabs。

主畫面：

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
        Instagram
```

Recent 同時是 Audit Trail。

使用者可以知道：

> Cayana 最近讀到了什麼、做了什麼。

---

## 21. Background Architecture

核心要求：

> **99% 的時間 Cayana 幾乎不消耗 CPU。**

正常流程：

Media / Share event  
→ enqueue processing  
→ OCR / STT / extraction  
→ persist  
→ action  
→ notification  
→ idle

禁止：

- 高頻 polling
- 持續掃描整個檔案系統
- LLM 常駐
- 不必要的長時間背景運算

---

## 22. 外部 Agent / MCP：未來方向，不屬 MVP

Cayana 的長期價值之一，是未來可以成為不同 LLM 或 Agent 的 Personal Memory Layer。

但 MVP 不做這件事。

未來若加入 MCP 或其他 connector：

只提供 Read-only：

```text
search_memory()
read_memory()
```

永遠不需要讓 Agent：

```text
create_memory()
update_memory()
delete_memory()
```

Memory 的寫入來源仍是：

- Screenshot
- Photo
- Recording
- Shared URL / file
- 使用者明確輸入

不是 Agent 的推理結果。

---

## 23. MVP 成功標準

最重要的完整場景：

1. 新使用者安裝 Cayana
2. 60 秒內完成設定
3. 在任何 App 看到：
   - 10/18 19:30
   - XX Live
   - 台北流行音樂中心
4. 按系統 Screenshot
5. 不打開 Cayana
6. 收到：
   - 已加入行事曆
   - XX Live · 10/18 19:30
   - 復原
7. Calendar 中 Event 正確存在
8. Cayana 搜尋 `XX Live`
9. 找得到該 Memory
10. 原始 Screenshot 被刪除
11. Memory 仍可搜尋
12. 執行 Google Drive Backup
13. 在乾淨資料庫 Restore
14. Memory 回來
15. 在 Cayana Ask 中問：
    - 「我十月是不是有一場演唱會？」
16. Retrieval 找到該 Memory
17. GPT 類 LLM 正確回答並附上來源 Memory

這條完整流程可靠成立：

> **Cayana MVP 即達成。**

---

## 24. 最終定義

Camera 負責看。

Recorder 負責聽。

Browser 與 Social Apps 負責讓人接觸資訊。

Calendar 負責時間。

LLM 負責理解與回答。

而 Cayana：

> **負責把沿路散落的碎片拾起來，讓它們不再消失。**
