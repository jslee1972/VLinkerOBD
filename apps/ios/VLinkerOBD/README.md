# vLinker OBD iOS MVP

SwiftUI + CoreBluetooth MVP：

- 掃描 BLE 裝置
- 優先尋找 vLinker / V-LINK
- 自動探索 GATT Service / Characteristic
- 支援常見 BLE-UART profile 與 fallback discovery
- 初始化 ELM/STN 相容 OBD 介面
- 每 200 ms 輪詢標準 PID `010D`
- 顯示即時 km/h
- 顯示 raw response 與 BLE/OBD Log
- 手動 AT / OBD 指令

## Xcode

建立 iOS SwiftUI 專案後加入：
- `VLinkerOBDApp.swift`
- `ContentView.swift`
- `OBDBLEManager.swift`
- `OBDParser.swift`

Target Info 加入 Bluetooth Always Usage Description：
`此 App 需要藍牙連接 OBD-II 車輛轉接器。`

請使用實體 iPhone 測試 BLE。

## OBD 初始化

```text
ATZ
ATE0
ATL0
ATS0
ATH0
ATSP0
0100
```

接著輪詢 `010D`。例如 `41 0D 3C` = 60 km/h。
