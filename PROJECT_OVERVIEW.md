# vLinker OBD Dashboard

## Goal

建立跨平台車輛即時 Dashboard：

- iOS App：SwiftUI + CoreBluetooth
- Windows App：C#/.NET/WPF
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
│   └── windows/
│       └── VLinkerOBD.Windows/
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
