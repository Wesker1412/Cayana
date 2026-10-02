# Cayana — Personal Memory Layer

> **「你只管生活，Cayana 幫你記得。」**

Cayana 是一個原生 Android Personal Memory Layer（個人記憶層）。它不是筆記軟體、相簿或錄音 App，而是一層安靜存在於手機背後的記憶收集與檢索系統：使用者照常截圖、拍照、錄音、分享網址；Cayana 在本機自動辨識碎片、萃取結構化記憶，並在使用者需要時找回記憶。

---

## 全域產品原則與邊界

1. **隱私優先（Privacy First）**：
   - 絕不將使用者的私人 OCR、Transcript 或 Memory 原文記錄至一般 Log。
   - Crash Report 與 Analytics 嚴禁夾帶 Memory 原文。
   - 原始媒體檔案預設不上傳伺服器。
2. **Index, don't duplicate**：
   - Cayana 儲存的是結構化資訊（OCR 文字、Metadata、時間地點實體、搜尋索引），而非重複儲存大型原始多媒體。
   - **原始檔刪除後，已建立的 Memory 絕不跟著消失**（標記 `sourceExists = false`）。
3. **Local-first**：
   - OCR、語音轉文字、搜尋與資料庫儲存優先在本機完成。
4. **Agent 與 LLM 邊界**：
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

## 分階段開發進度

- [x] **Stage 0 — 專案基礎與架構骨架** (當前完成)
- [ ] **Stage 1 — Onboarding、權限與來源設定** (下一階段)
- [ ] **Stage 2 — Source Watcher 基礎設施**
- [ ] **Stage 3 — Local OCR 整合**
- [ ] **Stage 4 — Killer Feature: Screenshot → Calendar 引擎**
- [ ] **Stage 5 — Local STT (語音轉文字) 整合**
- [ ] **Stage 6 — Android Share Sheet 整合**
- [ ] **Stage 7 — 本機搜尋引擎**
- [ ] **Stage 8 — Google Drive 加密備份**
- [ ] **Stage 9 — Cayana Cloud 與 RAG Ask 整合**
