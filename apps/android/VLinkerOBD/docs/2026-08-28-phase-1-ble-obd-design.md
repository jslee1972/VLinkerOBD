# Android Phase 1 BLE／OBD 設計規格

## 目標

在 `apps/android/VLinkerOBD/` 建立可由 Android Studio on Windows 編譯及部署的 Kotlin／Jetpack Compose App。第一階段支援掃描並手動選擇 BLE vLinker 裝置、探索 GATT、初始化 ELM/STN、以單一命令佇列每 200 ms 輪詢 `010D`，並以繁體中文 UI 顯示即時車速、原始回應與診斷紀錄。

## 範圍

- 僅修改 `apps/android/VLinkerOBD/`。
- 不修改 `apps/ios/` 或 `shared/`。
- 不實作背景 Service、自動重連、資料持久化、CSV、進階 PID、CarPlay 或 Android Auto。
- BLE 與 OBD 實體通訊以 Android 手機及 vLinker 轉接器驗證；模擬器只驗證 UI 與權限失敗流程。

## 技術與平台

- Kotlin、Jetpack Compose、Android BLE API。
- Android Studio on Windows、Java 17、compileSdk／targetSdk 35、minSdk 26。
- Kotlin Coroutines 與 `StateFlow` 管理非同步工作及不可變 UI 狀態。
- Android 12 以上要求 `BLUETOOTH_SCAN` 與 `BLUETOOTH_CONNECT`；Android 11 以下使用既有 Bluetooth 與定位權限宣告。

## 元件設計

### BLE 傳輸

`ble/BleObdManager.kt` 負責 BLE 掃描、連線、GATT 探索、notification 訂閱、資料接收與寫入。掃描結果依裝置位址去重；名稱比對不分大小寫，包含 `vLinker`、`V-LINK` 或 `VLINK` 的裝置排在清單頂端，但不自動連線。

連線後必須列舉並記錄所有 service 與 characteristic UUID 和 properties。候選接收端包含 Notify 或 Indicate，候選傳送端包含 Write 或 Write Without Response。選擇相容的可通知及可寫入組合，不依賴單一固定 UUID。若找不到相容組合，停止初始化、保留完整 GATT log，讓使用者中斷或重新掃描。

BLE 設定操作必須序列化：完成 CCCD descriptor write 並收到成功 callback 後，才能開始 characteristic write。關閉畫面或 ViewModel 時停止掃描、取消工作並關閉 `BluetoothGatt`。

### OBD 命令佇列

`obd/ObdCommandQueue.kt` 保證同一時間只有一個 ELM/STN 指令。傳送時補上 `\r`，接收端可累積多段 BLE notification，且只有在看到 `>` prompt 後才完成目前命令並傳送下一個。

每個命令等待 2 秒，逾時後重試一次。初始化依序執行：

1. `ATZ`
2. `ATE0`
3. `ATL0`
4. `ATS0`
5. `ATH0`
6. `ATSP0`
7. `0100`

初始化命令第二次逾時會中斷 GATT 連線。初始化完成後每 200 ms 排程一次 `010D`；輪詢第二次逾時只記錄錯誤並進入下一輪。手動命令會暫停自動輪詢、優先執行，收到 prompt 或最終逾時後自動恢復輪詢。

### 回應解析與資料模型

`obd/ObdResponseParser.kt` 保留原始回應，並能處理命令 echo、空格、換行、大小寫及 `>` prompt。解析器從正規化資料辨識 `41 0D XX`，將十六進位 `XX` 轉成 `0..255 km/h`；不完整或不合法資料不更新車速。

`model/VehicleData.kt` 提供型別化 `speedKph`。Compose UI 只讀取 `VehicleData`，不可直接從 raw response 計算車速。

### 狀態與紀錄

`ui/DashboardViewModel.kt` 協調權限、掃描、連線、初始化、輪詢與手動命令，並對 UI 暴露不可變 `DashboardUiState`。連線狀態需涵蓋未連線、掃描中、連線中、探索 GATT、初始化、就緒、已中斷與錯誤。

BLE、GATT 與 OBD 訊息合併成記憶體 log，僅保留最近 500 筆；App 關閉後清除。GATT 中斷時停止命令佇列與輪詢、清除 characteristic 參照，但保留當次畫面 log。

## Compose UI

單一繁體中文 Dashboard 畫面包含：

- 連線狀態與錯誤提示。
- 掃描／停止掃描按鈕。
- 裝置名稱、位址、RSSI、vLinker 優先標記及手動連線按鈕。
- 大型即時車速；尚無資料時顯示 `-- km/h`。
- 最近一次 raw response。
- 手動 AT／OBD 指令輸入與傳送按鈕；輸入會去除多餘空白並轉成大寫，空指令不可傳送。
- 可捲動 BLE／OBD log 與「清除紀錄」按鈕。

`MainActivity` 只負責 Activity result 權限流程與掛載 Compose；`ui/DashboardScreen.kt` 只呈現狀態及送出使用者事件。

## 錯誤處理

- Bluetooth 未開啟、權限拒絕或掃描失敗：顯示繁體中文原因與可重試操作。
- 找不到 GATT 傳輸 characteristic：停止初始化並保留探索紀錄。
- 初始化最終逾時：記錄失敗並中斷連線。
- `010D` 最終逾時：記錄失敗但繼續後續輪詢。
- GATT 非預期斷線：停止所有 OBD 工作並將 UI 更新為已中斷。

## 測試與完成條件

JVM 單元測試覆蓋：

- vLinker 名稱辨識與優先排序。
- `41 0D XX` 正常、分行、帶 echo、大小寫與無效回應。
- command queue 的單一在途命令、分段 prompt、逾時、一次重試與手動命令優先。
- log 永遠不超過 500 筆。

Compose 測試覆蓋未連線、掃描中、已連線、錯誤、無車速與有車速狀態，以及手動命令事件。

完成時必須執行 `testDebugUnitTest` 與 `assembleDebug`，修正所有 compile error。實體手機驗證需確認權限、掃描排序、手動連線、GATT fallback discovery、完整初始化、200 ms 車速輪詢、手動命令插隊、raw response 與 log 顯示。
