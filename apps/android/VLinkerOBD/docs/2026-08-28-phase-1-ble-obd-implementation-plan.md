# Android Phase 1 BLE／OBD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可在實體 Android 手機掃描、手動連線 vLinker、探索 GATT、初始化 ELM/STN、輪詢車速並顯示診斷資訊的第一階段 App。

**Architecture:** `BleObdManager` 封裝 Android BLE callback；`ObdCommandQueue` 序列化所有命令並以 `>` prompt 完成回應；`ObdResponseParser` 產生型別化 `VehicleData`。`DashboardViewModel` 組合元件並暴露單一 `StateFlow<DashboardUiState>`，Compose 只渲染狀態及送出事件。

**Tech Stack:** Kotlin 2.0.21、Jetpack Compose、Android BLE API、Kotlin Coroutines／StateFlow、JUnit 4、kotlinx-coroutines-test、Compose UI Test、Java 17、AGP 8.7.3。

**Spec:** `apps/android/VLinkerOBD/docs/2026-08-28-phase-1-ble-obd-design.md`

## Global Constraints

- 僅修改 `apps/android/VLinkerOBD/`；不可修改 `apps/ios/` 或 `shared/`。
- 使用者可見文字使用繁體中文；識別字使用英文。
- minSdk 26、compileSdk 35、targetSdk 35、JVM target 17。
- GATT 必須依 properties fallback discovery，不可寫死單一 UUID。
- 同時只允許一個 OBD 指令；必須收到 `>` 或最終逾時才能完成。
- 每次等待 2 秒、最多兩次 attempt；初始化最終失敗會中斷，輪詢最終失敗則繼續。
- 初始化固定為 `ATZ`、`ATE0`、`ATL0`、`ATS0`、`ATH0`、`ATSP0`、`0100`。
- 初始化後每 200 ms 輪詢 `010D`；手動命令暫停輪詢並優先執行。
- Log 僅存在記憶體並限制最近 500 筆。
- 不加入背景 Service、自動重連、持久化、CSV、進階 PID 或 Android Auto。

---

## File Structure

```text
apps/android/VLinkerOBD/
├── .gitignore
├── gradle/wrapper/*、gradlew、gradlew.bat
├── app/build.gradle.kts
├── app/src/main/AndroidManifest.xml
├── app/src/main/java/com/jslee1972/vlinkerobd/
│   ├── MainActivity.kt
│   ├── ble/
│   │   ├── BleModels.kt
│   │   ├── BleDeviceRanking.kt
│   │   ├── GattCharacteristicSelector.kt
│   │   ├── BleObdClient.kt
│   │   └── BleObdManager.kt
│   ├── model/VehicleData.kt
│   ├── obd/
│   │   ├── ObdTransport.kt
│   │   ├── ObdCommandQueue.kt
│   │   └── ObdResponseParser.kt
│   └── ui/
│       ├── DashboardUiState.kt
│       ├── BoundedLog.kt
│       ├── DashboardViewModel.kt
│       └── DashboardScreen.kt
└── app/src/test/java/、app/src/androidTest/java/
```

### Task 1: 可重現建置與測試基礎

**Files:**
- Create: `apps/android/VLinkerOBD/.gitignore`
- Create: `apps/android/VLinkerOBD/gradlew`
- Create: `apps/android/VLinkerOBD/gradlew.bat`
- Create: `apps/android/VLinkerOBD/gradle/wrapper/gradle-wrapper.jar`
- Create: `apps/android/VLinkerOBD/gradle/wrapper/gradle-wrapper.properties`
- Modify: `apps/android/VLinkerOBD/app/build.gradle.kts`

**Interfaces:**
- Produces: Gradle 8.9 wrapper、JUnit/coroutine/Compose test runtime。

- [ ] **Step 1: 產生 Gradle 8.9 wrapper**

```powershell
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat" wrapper --gradle-version 8.9 --distribution-type bin
```

確認 `distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip`。

- [ ] **Step 2: 建立 Android .gitignore**

```gitignore
.gradle/
.idea/
local.properties
**/build/
captures/
.externalNativeBuild/
.cxx/
```

- [ ] **Step 3: 加入依賴**

在 `android` 加入 `testOptions { unitTests.isReturnDefaultValues = true }`，在 `dependencies` 加入：

```kotlin
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

- [ ] **Step 4: 驗證並 Commit**

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
git add apps/android/VLinkerOBD
git commit -m "build(android): add reproducible Gradle test setup"
```

Expected: `BUILD SUCCESSFUL`，產生 `app/build/outputs/apk/debug/app-debug.apk`。

---

### Task 2: 車輛資料與車速解析

**Files:**
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/model/VehicleData.kt`
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/obd/ObdResponseParser.kt`
- Test: `apps/android/VLinkerOBD/app/src/test/java/com/jslee1972/vlinkerobd/obd/ObdResponseParserTest.kt`

**Interfaces:**
- Produces: `data class VehicleData(val speedKph: Int? = null)`。
- Produces: `ObdResponseParser.normalize(raw: String): String`。
- Produces: `ObdResponseParser.parseSpeedKph(raw: String): Int?`。

- [ ] **Step 1: 寫失敗測試**

```kotlin
@Test fun parsesSpacedSpeed() = assertEquals(40, parseSpeedKph("41 0D 28\r>"))
@Test fun parsesEchoAndCompactLowercase() = assertEquals(100, parseSpeedKph("010D\r410d64\r>"))
@Test fun parsesSplitLines() = assertEquals(10, parseSpeedKph("41 0D\r0A\r>"))
@Test fun rejectsMissingByte() = assertNull(parseSpeedKph("41 0D\r>"))
@Test fun rejectsInvalidHex() = assertNull(parseSpeedKph("41 0D GG\r>"))
@Test fun rejectsDifferentPid() = assertNull(parseSpeedKph("41 0C 12 34\r>"))
```

- [ ] **Step 2: 確認紅燈**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.ObdResponseParserTest"
```

Expected: FAIL，類別尚不存在。

- [ ] **Step 3: 最小實作**

正規化需轉大寫、移除 echo/prompt/非十六進位分隔；搜尋 `410D` 後讀兩碼，以 `toIntOrNull(16)` 解析，不合法回傳 `null`。

- [ ] **Step 4: 綠燈並 Commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.ObdResponseParserTest"
git add apps/android/VLinkerOBD/app/src
git commit -m "feat(android): parse OBD vehicle speed"
```

---

### Task 3: 裝置排名與 500 筆 Log

**Files:**
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/ble/BleModels.kt`
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/ble/BleDeviceRanking.kt`
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/ui/BoundedLog.kt`
- Test: `apps/android/VLinkerOBD/app/src/test/java/com/jslee1972/vlinkerobd/ble/BleDeviceRankingTest.kt`
- Test: `apps/android/VLinkerOBD/app/src/test/java/com/jslee1972/vlinkerobd/ui/BoundedLogTest.kt`

**Interfaces:**
- Produces: `ScannedBleDevice(address, name, rssi, isPreferred)`。
- Produces: `BleDeviceRanking.isPreferred(name)`、`rank(devices)`。
- Produces: `BoundedLog(maxEntries = 500).append/clear/entries`。

- [ ] **Step 1: 寫失敗測試**

驗證 `vLinker MC+`、`V-LINK`、`my-vlink-adapter` 為 preferred，`OBDII` 與 `null` 不是；preferred 先依 RSSI 降冪，再以 address 穩定排序。

```kotlin
@Test fun retainsLatestFiveHundredEntries() {
    val log = BoundedLog()
    repeat(510) { log.append("訊息 $it") }
    assertEquals(500, log.entries.size)
    assertEquals("訊息 10", log.entries.first())
    assertEquals("訊息 509", log.entries.last())
}
```

- [ ] **Step 2: 確認紅燈**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.BleDeviceRankingTest" --tests "*.BoundedLogTest"
```

- [ ] **Step 3: 最小實作**

名稱用 `Regex("V[\\s-]?LINK(?:ER)?", IGNORE_CASE)`；BoundedLog 每次 append 後移除最舊資料直到不超過容量，對外回傳 copy。

- [ ] **Step 4: 綠燈並 Commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.BleDeviceRankingTest" --tests "*.BoundedLogTest"
git add apps/android/VLinkerOBD/app/src
git commit -m "feat(android): rank vLinker devices and bound logs"
```

---

### Task 4: GATT fallback discovery

**Files:**
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/ble/GattCharacteristicSelector.kt`
- Test: `apps/android/VLinkerOBD/app/src/test/java/com/jslee1972/vlinkerobd/ble/GattCharacteristicSelectorTest.kt`

**Interfaces:**
- Produces: `GattCharacteristicCandidate(serviceUuid, characteristicUuid, canNotify, canIndicate, canWrite, canWriteWithoutResponse)`。
- Produces: `GattSelection(notify, write)` 與 `select(candidates): GattSelection?`。

- [ ] **Step 1: 寫失敗測試**

涵蓋 Notify+Write、Indicate+WriteWithoutResponse、同一 characteristic 同時收送、跨 characteristic 配對、缺接收端、缺傳送端。候選只含 UUID 與 boolean properties，測試中不得使用產品固定 UUID。

- [ ] **Step 2: 確認紅燈**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.GattCharacteristicSelectorTest"
```

- [ ] **Step 3: 實作 deterministic selector**

Notify 優先於 Indicate；WriteWithoutResponse 優先於 Write；同 service 配對優先；其餘依 UUID 字串排序。無完整收送組合回傳 `null`。

- [ ] **Step 4: 綠燈並 Commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.GattCharacteristicSelectorTest"
git add apps/android/VLinkerOBD/app/src
git commit -m "feat(android): discover GATT transport characteristics"
```

---

### Task 5: 單一 OBD command queue

**Files:**
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/obd/ObdTransport.kt`
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/obd/ObdCommandQueue.kt`
- Test: `apps/android/VLinkerOBD/app/src/test/java/com/jslee1972/vlinkerobd/obd/ObdCommandQueueTest.kt`

**Interfaces:**
- Produces: `ObdTransport.write(bytes): Result<Unit>` 與 `incoming: Flow<ByteArray>`。
- Produces: `ObdCommand(text, kind, timeout = 2.seconds, maxAttempts = 2)`。
- Produces: `execute(command): ObdCommandResult`、`cancelCurrent()`、`close()`。

- [ ] **Step 1: 寫單一在途命令測試**

Fake transport 以 `MutableSharedFlow<ByteArray>` 注入 notification。啟動兩個 execute，第一個收到 `>` 前 write count 必須是 1；完成後才寫第二個。

- [ ] **Step 2: 寫 prompt/分段/逾時測試**

注入 `"41 0"`、`"D 28\r"` 不得完成；注入 `">"` 才回傳完整 raw。用 `runTest` 與 `advanceTimeBy(2_001)` 驗證只重試一次，第二次逾時回傳 Timeout。

- [ ] **Step 3: 確認紅燈**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.ObdCommandQueueTest"
```

- [ ] **Step 4: 實作 actor-style queue**

單一 coroutine 消費 `Channel<PendingCommand>`。每次 attempt 清空 buffer、寫入 `trim().uppercase() + "\r"`，用 `withTimeout` collect incoming 至 buffer 包含 `>`；完成或最終失敗後才取下一項。

- [ ] **Step 5: 綠燈並 Commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.ObdCommandQueueTest"
git add apps/android/VLinkerOBD/app/src
git commit -m "feat(android): serialize OBD commands by prompt"
```

---

### Task 6: Android BLE manager

**Files:**
- Modify: `apps/android/VLinkerOBD/app/src/main/AndroidManifest.xml`
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/ble/BleObdClient.kt`
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/ble/BleObdManager.kt`

**Interfaces:**
- Consumes: `BleDeviceRanking`、`GattCharacteristicSelector`。
- Produces: `BleObdClient : ObdTransport`，包含 devices、connectionState、logs flows 及 scan/connect/disconnect 方法。
- Implements: `BleObdManager : BleObdClient`。
- Produces: `devices`、`connectionState`、`logs` flows，及 `startScan/stopScan/connect/disconnect`。

- [ ] **Step 1: 驗證 Manifest 權限矩陣**

API 31+：`BLUETOOTH_SCAN`（neverForLocation）、`BLUETOOTH_CONNECT`；API <=30：Bluetooth、Bluetooth Admin、Fine Location；保留 BLE feature。

- [ ] **Step 2: 實作掃描**

用 `BluetoothLeScanner`，address 去重並以 ranking 排序；記錄開始、新裝置、RSSI、錯誤、停止。Bluetooth 關閉、adapter 缺失、SecurityException 轉成明確錯誤狀態。

- [ ] **Step 3: 實作連線與探索**

`device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)` 後 discover services。逐一記錄 service UUID、characteristic UUID 與 NOTIFY/INDICATE/WRITE/WRITE_NO_RESPONSE properties，再交 selector。無配對時記錄錯誤並關閉。

- [ ] **Step 4: 序列化 CCCD 與 write**

先 set notification，再寫 CCCD；只有 descriptor callback 成功才 Ready。每次 characteristic write 等前一次 callback；API 33+ 使用新 overload，舊版集中相容處理。callback 收到的 byte array 先複製再送 incoming flow。

- [ ] **Step 5: 編譯並 Commit**

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
git add apps/android/VLinkerOBD/app/src
git commit -m "feat(android): scan and connect through BLE GATT"
```

---

### Task 7: ViewModel、初始化、輪詢與手動命令

**Files:**
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/ui/DashboardUiState.kt`
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/ui/DashboardViewModel.kt`
- Test: `apps/android/VLinkerOBD/app/src/test/java/com/jslee1972/vlinkerobd/ui/DashboardViewModelTest.kt`

**Interfaces:**
- Consumes: `BleObdClient`、queue、parser、BoundedLog；測試使用 fake client，不依賴 Android Bluetooth 類別。
- Produces: `StateFlow<DashboardUiState>` 與 scan/connect/disconnect/sendManualCommand/clearLogs events。

- [ ] **Step 1: 定義 state**

```kotlin
data class DashboardUiState(
    val connectionLabel: String = "尚未連線",
    val devices: List<ScannedBleDevice> = emptyList(),
    val connectedDeviceName: String? = null,
    val vehicleData: VehicleData = VehicleData(),
    val rawResponse: String = "",
    val logs: List<String> = emptyList(),
    val isScanning: Boolean = false,
    val isReady: Boolean = false,
    val errorMessage: String? = null,
)
```

- [ ] **Step 2: 寫初始化與輪詢失敗測試**

BLE Ready 後斷言七個初始化命令嚴格排序；全部成功前不可送 `010D`。成功後以 test scheduler 驗證完成一輪、delay 200 ms 才送下一輪，避免 ticker 堆積。

- [ ] **Step 3: 寫手動與錯誤失敗測試**

`sendManualCommand(" ati ")` 必須暫停 poll、送 `ATI`、完成後恢復。空白不送。初始化 Timeout 呼叫 disconnect；poll Timeout 只加 log；`41 0D 28` 更新 speed=40 與 raw。

- [ ] **Step 4: 確認紅燈**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.DashboardViewModelTest"
```

- [ ] **Step 5: 實作 orchestration**

以單一 `pollingJob` 在每次完成後 delay 200 ms；手動命令用 `Mutex` 暫停/重啟 job。所有 BLE/OBD log 進同一個 500 筆 BoundedLog。`onCleared` 關閉 queue 與 manager。

- [ ] **Step 6: 綠燈並 Commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.DashboardViewModelTest"
git add apps/android/VLinkerOBD/app/src
git commit -m "feat(android): initialize and poll OBD speed"
```

---

### Task 8: 繁體中文 Compose UI 與權限

**Files:**
- Modify: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/MainActivity.kt`
- Create: `apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/ui/DashboardScreen.kt`
- Test: `apps/android/VLinkerOBD/app/src/androidTest/java/com/jslee1972/vlinkerobd/ui/DashboardScreenTest.kt`

**Interfaces:**
- Consumes: `DashboardUiState` 與 ViewModel events。
- Produces: 單畫面繁體中文 Dashboard 與版本相依權限 request。

- [ ] **Step 1: 寫 Compose 測試**

固定 state 驗證狀態、`-- km/h`、preferred 標記、`40 km/h`、raw、log、手動輸入與 callback。加入 tags：`connection_status`、`speed_value`、`raw_response`、`manual_command`、`send_command`、`obd_log`。

- [ ] **Step 2: 實作 DashboardScreen**

Scaffold 內依序呈現狀態、掃描控制、LazyColumn 裝置、車速、raw、OutlinedTextField/傳送、log/清除。所有文字用繁體中文，未知車速顯示 `-- km/h`。

- [ ] **Step 3: 實作權限 launcher**

API >=31 請求 Scan/Connect，較舊版本請求 Fine Location。按掃描但未授權時才 request；拒絕顯示「需要藍牙掃描權限才能尋找裝置」，不可崩潰。

- [ ] **Step 4: 掛載 ViewModel 並驗證**

使用 `viewModel()` 與 lifecycle-aware state collection。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
# 連接 emulator 或手機後：
.\gradlew.bat connectedDebugAndroidTest
```

- [ ] **Step 5: Commit**

```powershell
git add apps/android/VLinkerOBD/app/src
git commit -m "feat(android): add Traditional Chinese OBD dashboard"
```

---

### Task 9: 完整驗證與實體手機文件

**Files:**
- Modify: `apps/android/VLinkerOBD/README.md`

**Interfaces:**
- Produces: Windows/Android Studio build 與實體 vLinker 驗收指南。

- [ ] **Step 1: 全新 build/test**

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug
```

Expected: `BUILD SUCCESSFUL`、tests 全過、APK 存在。

- [ ] **Step 2: 驗證變更範圍**

```powershell
git diff --check
git diff --name-only origin/main...HEAD
```

Expected: 所有路徑皆在 `apps/android/VLinkerOBD/`。

- [ ] **Step 3: README 加入實體手機步驟**

明列：USB 偵錯、Android Studio 選手機、權限、Bluetooth、插入並喚醒 vLinker、掃描/手選、GATT log、七步初始化、010D/車速、ATI 手動插隊、拔除 adapter。

- [ ] **Step 4: 執行實機驗收**

```text
[ ] vLinker 名稱裝置優先排序
[ ] 使用者可手動選擇裝置
[ ] GATT log 列出所有 service/characteristic/properties
[ ] 不靠固定 UUID 找到 notify/indicate 與 write
[ ] 初始化七命令順序正確
[ ] 每個命令等待 > 才送下一個
[ ] 010D 約每 200 ms 且無並行積壓
[ ] 41 0D XX 正確更新 km/h
[ ] ATI 暫停輪詢並於完成後恢復
[ ] raw response 與最近 500 筆 log 正常
[ ] 權限拒絕、Bluetooth 關閉、斷線均顯示繁中錯誤且不崩潰
```

- [ ] **Step 5: Commit、同步、再次驗證**

```powershell
git add apps/android/VLinkerOBD
git commit -m "docs(android): add physical vLinker test guide"
git pull --rebase origin main
.\gradlew.bat testDebugUnitTest assembleDebug
git status --short --branch
```

Expected: tests/build 成功且工作樹乾淨。沒有完成硬體測試時，交付報告必須列為已知限制。
