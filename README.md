# Cayana — Personal Memory Layer

> **「你只管生活，Cayana 幫你記得。」**

Cayana 是一個原生 Android Personal Memory Layer（個人記憶層）。它不是筆記軟體、相簿或錄音 App，而是一層安靜存在於手機背後的記憶收集與檢索系統：使用者照常截圖、拍照、錄音、分享網址；Cayana 在本機自動辨識碎片、萃取結構化記憶，並在使用者需要時找回記憶。

---

## 全域產品原則與邊界

1. **隱私優先（Privacy First）**：
   - 絕不將使用者的私人 OCR、Transcript 或 Memory 原文記錄至一般 Log。
   - `CayanaLogger` 內建自動過濾防護（Privacy Guard），防範意外將含 `rawText` / `transcript` 的記憶物件直接印出。
   - Crash Report 與 Analytics 嚴禁夾帶 Memory 原文。
   - 原始媒體檔案預設不上傳伺服器。
2. **禁止 Android 自動備份私人 Memory DB**：
   - 正式客戶端加密備份於 Stage 6 實作前，App 設定 `android:allowBackup="false"`。
   - `data_extraction_rules.xml` 與 `backup_rules.xml` 明確排除 `database`、`sharedpref`、`file`、`root`，防止未加密私人記憶進入 Android 雲端備份。
3. **資料庫遷移政策（Database Migration Policy）**：
   - 嚴格禁止使用 `fallbackToDestructiveMigration()`。
   - Cayana 是 Personal Memory Layer，任何 App 升級絕不能因 migration 缺失而靜默刪除使用者記憶。
   - 啟用 Room Schema Export（路徑：`app/schemas/`），所有未來的資料庫結構更動皆必須提供嚴格測試的 Versioned `Migration`。
4. **Index, don't duplicate**：
   - Cayana 儲存的是結構化資訊（OCR 文字、Metadata、時間地點實體、搜尋索引），而非重複儲存大型原始多媒體。
   - **原始檔刪除後，已建立的 Memory 絕不跟著消失**（標記 `sourceExists = false`）。
5. **Local-first**：
   - OCR、語音轉文字、搜尋與資料庫儲存優先在本機完成。
6. **Agent 與 LLM 邊界**：
   - LLM 不直接存取資料庫，未來若加入 Agent 也僅允許 **Read-only Memory access**。
   - LLM 不持有日曆（Calendar）憑證。

---

## 架構設計與 Package 邊界

專案採用 Clean Architecture 與 Repository Pattern，核心資料層完全透過 `interface` 抽象解耦，無 God Class，各模組職責清晰：

```
com.cayana/
├── CayanaApplication.kt       # Application 進入點，初始化 Koin DI
├── MainActivity.kt            # 單一 Activity，承載 Compose NavHost
├── core/
│   ├── common/                # Result<T>、CoroutineDispatchers 介面與實作
│   ├── logging/               # PrivacySanitizer、CayanaLogger 隱私記錄防護
│   └── di/                    # Koin 依賴注入模組（App、Database、Repository、ViewModel）
├── source/                    # 來源抽象（SourceType、SourceItem、SourceWatcher）
├── processing/                # 辨識處理邊界（OcrEngine、SttEngine、EntityExtractor、DateTimeCandidateParser）
├── memory/
│   ├── model/                 # 統一 MemoryItem 領域模型、EventCandidate
│   ├── data/                  # Room Database、MemoryEntity、MemoryDao、TypeConverters
│   └── repository/            # MemoryRepository 介面與 RoomMemoryRepository 實作
├── search/                    # 搜尋模組（SearchQuery、SearchResult、MemorySearchEngine）
├── actions/                   # 動作抽象（ActionExecutor、ActionType、ActionResult）
├── backup/                    # 備份抽象（BackupService、BackupState）
├── ai/                        # AI 邊界（ReadOnlyMemoryProvider、Entitlement、LLMService）
└── ui/
    ├── theme/                 # Material 3 主題設定（Color、Typography、Theme）
    ├── navigation/            # Compose Navigation（Screen、CayanaNavHost）
    ├── home/                  # 首頁 UI（HomeScreen、HomeViewModel、HomeUiState）
    └── settings/              # 設定頁 Shell（SettingsScreen、SettingsViewModel、SettingsRepository）
```

---

## 開發環境需求

- **JDK**：OpenJDK 17（建議 Eclipse Temurin 或 Microsoft OpenJDK 17）
- **Android SDK**：
  - `compileSdk = 35`
  - `targetSdk = 35`
  - `minSdk = 26` (Android 8.0+)
  - `build-tools = 35.0.0`
- **Gradle**：8.10.2（專案內建 Gradle Wrapper `./gradlew`）
- **Kotlin**：2.0.21
- **UI Toolkit**：Jetpack Compose（Compose BOM 2024.10.00, Material 3）
- **Database**：AndroidX Room 2.6.1 (KSP)
- **Dependency Injection**：Koin 3.5.6

---

## 建置與測試指令

### 1. 執行單元測試
```bash
./gradlew test
# 或在 Windows PowerShell:
.\gradlew.bat test
```

### 2. 建置 Debug APK
```bash
./gradlew assembleDebug
# 或在 Windows PowerShell:
.\gradlew.bat assembleDebug
```
產出的 APK 位於：`app/build/outputs/apk/debug/app-debug.apk`

---

## CI / CD

專案設定 GitHub Actions 工作流程（`.github/workflows/ci.yml`），在每一次 Push 與 Pull Request 時自動執行：
- 代碼檢出與 JDK 17 環境安裝
- 單元測試（`./gradlew test`）
- Debug APK 編譯構建（`./gradlew assembleDebug`）

---

## 分階段開發進度 (對齊 Canonical Roadmap v0.2)

- [x] **Stage 0 — 專案基礎與架構骨架** (已通過二次驗收)
- [x] **Stage 1 — Onboarding、權限與來源設定** (當前進行)
- [ ] **Stage 2 — Screenshot → OCR → Memory**
- [ ] **Stage 3 — Screenshot → Calendar**
- [ ] **Stage 4 — Photos、Recordings 與背景處理**
- [ ] **Stage 5 — Share Sheet、統一搜尋與 Recent Activity**
- [ ] **Stage 6 — Google Drive 加密備份與還原**
- [ ] **Stage 7 — Cayana Cloud 基礎同步與濫用限制**
- [ ] **Stage 8 — GPT 類 LLM RAG Ask**
- [ ] **Stage 9 — AI 額度、訂閱與點數**
- [ ] **Stage 10 — Reliability、Security、Battery 與 MVP Release**
