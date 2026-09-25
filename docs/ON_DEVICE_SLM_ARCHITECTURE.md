# MyTypeless 端雲協同與端側小模型 (On-Device SLM) 架構設計規格書

**分支**: `feature/on-device-slm`  
**版本**: `v1.0-draft`  
**目標**: 為 MyTypeless 輸入法建立「端雲協同 (Hybrid AI)」與「離線降級備援 (Offline Fallback)」架構，整合 Google Gemini Nano (AICore) 與 Google Gemma 2 (2B / MediaPipe)，並結合 Android 原生端側語音辨識 (On-Device ASR)，實現無網環境下的無縫輸入體驗。

---

## 1. 核心願景與設計哲學 (Philosophy & Vision)

MyTypeless 的核心痛點是解決**台灣繁體中文與英文夾雜（Code-switching）**的流暢口語輸入。現行架構依賴高算力雲端管線（Groq Whisper-large-v3-turbo + Google Gemini 2.5 Flash），在連線狀態下具備極致的辨識度與語言重構能力。

然而，在以下場景中，純雲端架構面臨致命挑戰：
1. **弱網與無網（地下室、捷運、飛航模式、網路斷線）**：鍵盤輸入完全停擺。
2. **高頻短句輸入延遲**：每次輸入都要承擔來回網路延遲（RTT 300~800ms）。
3. **隱私與敏感資訊**：密碼、個人帳戶、私密對話不宜上傳至第三方 API。

本架構並非用端側模型「完全取代」雲端，而是採取**端雲協同（Hybrid Architecture）**：
> **「平常走雲端享受最高品質，斷網或超時時無縫降級至本機模型保底；短句極速直出，複雜段落智慧協同。」**

```mermaid
flowchart TD
    A[使用者說話完成 (Audio File / Stream)] --> B{網路狀態監控<br/>NetworkMonitor}
    
    %% 連線正常路徑
    B -->|有網路| C[Stage 1: 雲端 Groq Whisper API]
    C -->|2 秒超時熔斷保護| D{是否逾時 / 失敗?}
    D -->|成功| E[Stage 2: 雲端 Gemini 2.5 Flash]
    E -->|潤飾完成| F[輸出至游標<br/>InputConnection]
    
    %% 離線或降級路徑
    B -->|無網路 / 飛航模式| G[Stage 1: Android 原生離線 ASR<br/>OnDevice SpeechRecognizer]
    D -->|失敗 / 超時| G
    G --> H[Stage 2 預處理: 本機正則過濾<br/>Regex Filler Removal]
    H --> I{端側 SLM 適配器<br/>LocalAdaptiveEngine}
    I -->|支援 AICore (Pixel 8+/9/10)| J[Google Gemini Nano]
    I -->|非旗艦或離線擴充| K[MediaPipe + Gemma 2 2B]
    I -->|極致省電 / 記憶體吃緊| L[純本地規則直出 + 盤古之白]
    J --> F
    K --> F
    L --> F
```

---

## 2. 端側模型選型與硬體評估 (Model Selection & Hardware)

### 2.1 Google Gemini Nano (Android AICore) vs Google Gemma 2 (2B)

| 評估維度 | Google Gemini Nano (AICore) | Google Gemma 2 (2B IT) | 本地規則/正則 (Rule-based) |
| :--- | :--- | :--- | :--- |
| **運作平台** | Android AICore 系統級服務 | Google MediaPipe LLM Inference | 純 Kotlin / Java 標準庫 |
| **硬體支援** | Pixel 8+/9/10、Galaxy S24+ 等旗艦 | 任何具備 Vulkan/OpenCL 之 Android | 所有 Android 裝置 (API 28+) |
| **App 體積增加** | **0 MB**（模型常駐於系統鏡像） | **~1.3 GB - 2.0 GB**（需動態下載） | **0 MB** |
| **執行時期 RAM** | **0 MB 額外負擔**（由系統共用管理） | **~1.8 GB - 2.5 GB** | **< 1 MB** |
| **硬體加速** | 系統專屬 NPU / TPU (Tensor G3/G4/G5) | GPU (OpenCL/Vulkan) / CPU | CPU（奈秒級） |
| **推論延遲** | 極快 (~150 - 300ms) | 中等 (~400 - 900ms，視 GPU 而定) | 瞬間 (< 5ms) |
| **提示詞自訂彈性**| 受限於 Google System Prompt 規範 | 高度自訂（完整 System / Few-Shot） | 僅支援固定規則式清理 |

### 2.2 動態適配策略 (Adaptive Tiering Strategy)
1. **Tier 1 (旗艦極致體驗)**：優先檢測設備是否具備 `Android AICore` (Gemini Nano)。若有，直接以系統服務形式掛載，享受硬體 NPU 加速且不佔用 App 記憶體。
2. **Tier 2 (廣泛相容體驗)**：若設備無 AICore，提供「下載 Gemma 2 2B 離線套件」之擴充選項，透過 MediaPipe 執行。
3. **Tier 3 (安全保底體驗)**：若無 AICore 且未下載 Gemma 2，離線時自動啟用「本地正則剔除贅字 + 盤古之中英空格排版」，保證輸入法永遠可用、絕不崩潰。

---

## 3. 兩階段離線管線設計 (Two-Stage Offline Pipeline)

### 3.1 Stage 1: 離線語音轉文字 (Offline ASR)
* **雲端方案**：Groq Whisper API (`whisper-large-v3-turbo`)。
* **端側離線方案**：**Android 原生 `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)`**
  * **優點**：利用 Google 語音服務在 Android 本地下載的語言包（繁體中文 / 英文），零額外 App 下載體積，錄音即串流輸出。
  * **雙語混用痛點**：Android 原生離線 ASR 在面對中英夾雜時，可能偶有英文音譯為中文或遺漏標點之缺憾。
  * **補償機制**：將原生 ASR 的粗辨識文字，交付 Stage 2 端側小模型進行語意重構與專有名詞校正。

### 3.2 Stage 2: 混合微流水線 (Hybrid Micro-pipeline Polishing)
為兼顧端側算力與推論速度，文字整理不採取大模型重裝提示詞，而是採 **「本地規則預處理 + 極簡提示詞潤飾」**：

1. **Step 1 - 本地正則前置過濾 (Regex Pre-filtering)**：
   ```kotlin
   // 快速修剪口頭贅詞，大幅減少送入小模型的 Token 長度
   val fillerRegex = Regex("(^[，,。？?\\s]*(呃|啊|那個|就是說|然後其實|嗯)[，,。？?\\s]*)|([，,]\\s*(呃|啊|那個|就是說|嗯)\\s*)")
   ```
2. **Step 2 - 端側極簡提示詞 (Micro-Prompt for SLM)**：
   ```text
   你是一個專業的語音輸入後處理助手。請將以下語音識別的草稿進行最小限度的整理：
   1. 補上適當標點符號與斷句。
   2. 絕對不可翻譯任何英文單字或縮寫，原樣保留。
   3. 中文使用標準台灣繁體中文。
   4. 中英文與數字之間保留半形空格。
   直接輸出整理後的文字，不要包含任何多餘說明。
   草稿："{raw_text}"
   ```
3. **Step 3 - 盤古之白 (Pangu Spacing Post-processing)**：
   確保中英文字元邊界具備優雅的半形空格排版。

---

## 4. 網路偵測與自動降級策略 (Network Detection & Fallback Policy)

1. **常態連線監控 (NetworkStateMonitor)**：
   * 透過 `ConnectivityManager.registerDefaultNetworkCallback` 取得即時連線能力。
   * 若監聽到 `onLost` 或系統進入飛航模式，即時標記 `isOffline = true`。
2. **雲端超時熔斷 (2-Second Timeout Circuit Breaker)**：
   * 在弱網或封包遺失環境，雖然系統顯示有網路，但 API 請求可能掛起數十秒。
   * 設定 Groq / Gemini 雲端連線上限為 **2000ms**。一旦超時拋出 `SocketTimeoutException`，管線立即熔斷，自動回退調用本機離線管線。
3. **透明化狀態徽章 (Transparent Status Badge)**：
   * `⚡ Groq + 🤖 Gemini 潤飾完成`（完整雲端旗艦模式）
   * `📴 離線：原生 ASR + Gemini Nano 潤飾`（端側旗艦離線模式）
   * `📴 離線：原生 ASR + 本地規則直出`（基礎安全保底模式）

---

## 5. 生命週期與記憶體安全管理 (Lifecycle & Memory Management)

1. **Gemini Nano (AICore)**：
   * 屬於 Android 系統 IPC 綁定，無需 App 常駐維護權重記憶體，IME 隨開隨用。
2. **Gemma 2 (MediaPipe)**：
   * **按需延遲載入 (Lazy Load on Demand)**：平時雲端正常時不載入權重，僅在首次發生離線輸入時進行載入。
   * **閒置超時自動釋放 (Idle Eviction)**：設置 3 分鐘閒置計時器。若鍵盤收合且 3 分鐘內未再進行語音輸入，自動解構釋放模型記憶體，避免遭系統 LMK (Low Memory Killer) 終止背景進程。

---

## 6. 代碼抽象層設計 (Engine Abstraction Architecture)

在 `com.typeless.ime.engine` 套件中建立核心抽象介面與即時串流引擎：

```
com.typeless.ime.engine/
├── NetworkStateMonitor.kt          // 網路連線與品質即時監聽器 (ConnectivityManager.NetworkCallback)
├── PixelStreamingSttManager.kt      // Pixel 原生離線即時串流語音辨識 (SpeechRecognizer.createOnDeviceSpeechRecognizer)
├── SpeechToTextEngine.kt            // 語音轉文字抽象 (Groq / Android On-Device)
├── TextPolishingEngine.kt           // 文字潤飾抽象 (Gemini Cloud / Nano / Local Adaptive / Rule)
└── HybridPipelineCoordinator.kt     // 端雲協同調度器（超時處理、自動降級、Fast-Path）
```

---

## 7. 功能邊界與實測定義 (Functional Boundaries & Verification)

### 邊界 1：UI 實測開關與模式切換 (Dev Switch & Transparent Telemetry)
* **輸入**：使用者在鍵盤工具列點擊 `btn_mode_toggle`，或系統網路狀態斷開。
* **行為**：在 `☁️ 雲端模式` 與 `📱 端側模式` 間無縫切換，並持久化於 `SharedPreferences`。
* **輸出**：按鈕狀態與 Toast 提示即時更新，處理完畢後精確印出耗時：`📱 端側完成｜ASR: 210ms ➔ 潤飾: 35ms (總計 245ms)`。

### 邊界 2：Stage 1 離線串流語音辨識 (Pixel Live Streaming ASR)
* **輸入**：使用者按住錄音鍵說話。
* **行為**：透過 `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)` 即時收音，音量波形即時動畫，並即時輸出部分辨識預覽 (`🗣️ 正在辨識: ...`)。
* **輸出**：放開按鍵立即產出原始字串 `rawText` 與精確毫秒數 `asrDurationMs`。100% 離線運行、零網路請求。

### 3. Stage 2 本地文字潤飾與詞庫注入 (Local Polishing & Vocabulary Integration)
* **輸入**：`rawText` + 既有 SQLite 資料庫的高頻詞清單 `vocabularyRepository.getAllTermsForGemini(limit = 40)`。
* **行為**：本地 Regex 剔除贅字 ➔ 專屬詞庫強制校正大小寫與同音誤譯 ➔ 補齊標點符號與盤古之白 (中英空格)。
* **輸出**：高精度潤飾後文本 `finalText` 與潤飾耗時 `polishDurationMs`。既有詞庫 100% 完整保留。

### 邊界 4：輸入注入與閉環學習 (Cursor Injection & Continuous Learning)
* **輸入**：`finalText`。
* **行為**：透過 `InputConnection.commitText` 送入作用中的輸入框，並非同步觸發 `vocabularyRepository.recordUsageAsync(finalText)` 更新詞頻。
* **輸出**：游標落字，鍵盤按鈕復位，端側模式閉環完成。

---

## 8. 推進里程碑狀態 (Roadmap & Status)

- [x] **Milestone 1**: 建立端雲協同架構規格書與設計決策（完成）。
- [x] **Milestone 2**: 實作 `NetworkStateMonitor`、`SpeechToTextEngine` 與 `TextPolishingEngine` 抽象介面（完成）。
- [x] **Milestone 3**: 實作 `PixelStreamingSttManager` 原生離線即時串流語音辨識（完成）。
- [x] **Milestone 4**: 串接既有 SQLite 字彙庫進行端側專有詞模糊校正與盤古排版（完成）。
- [x] **Milestone 5**: 鍵盤介面整合「☁️ 雲端 / 📱 端側」一鍵實測開關與毫秒級狀態儀表（完成）。
- [x] **Milestone 6**: 完整除錯 APK 打包編譯驗證通過 (`assembleDebug` BUILD SUCCESSFUL)（完成）。
