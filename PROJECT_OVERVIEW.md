# vLinker OBD Dashboard

## Goal

建立跨平台車輛即時 Dashboard：

- iOS App：SwiftUI + CoreBluetooth，在 macOS / Xcode 開發
- Android App：Kotlin + Jetpack Compose，在 Windows / Android Studio 開發
- BLE：vLinker MC+ 等 OBD-II adapter
- Standard OBD-II first
- EV / Hybrid enhanced PID later
- Multi-brand vehicle profiles

## Repository layout

```text
VLinkerOBD/
├── AGENTS.md
├── PROJECT_OVERVIEW.md
├── ROADMAP.md
├── .gitignore
├── apps/
│   ├── ios/
│   │   └── VLinkerOBD/
│   └── android/
│       └── VLinkerOBD/
├── shared/
│   ├── vehicle-profiles/
│   └── protocol-docs/
└── docs/
```

## Current iOS MVP

Current prototype direction:
- BLE scan
- find vLinker
- GATT discovery
- write/notify characteristic auto-detection
- ELM/STN initialization
- poll `010D`
- parse vehicle speed
- show raw response/log

## Android direction

Android 版與 iOS 版應維持相同的資料模型與 OBD 行為，但 BLE 與 UI 使用 Android 原生實作：

- Kotlin
- Jetpack Compose
- Android Bluetooth LE APIs
- Android Studio on Windows

## Shared data target

UI on both OSes should eventually consume the same conceptual VehicleData fields:

```text
speedKPH
rpm
coolantTempC
engineLoadPercent
throttlePercent
controlModuleVoltage
stateOfChargePercent
batteryVoltage
batteryCurrent
batteryPowerKW
batteryTemperatureC
stateOfHealthPercent
gear
odometerKM
rangeKM
```

## Important constraint

BLE implementation is OS-specific. Vehicle PID/profile definitions should be portable and kept in `shared/`.
