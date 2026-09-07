# ROADMAP

## Phase 1 — Shared repository foundation
- [x] Separate iOS / Android / shared code
- [x] Root Codex instructions
- [x] Push repository to GitHub
- [ ] Clone on Mac
- [ ] Clone on Windows

## Phase 2 — iOS
- [ ] vLinker MC+ real hardware test
- [ ] command timeout/retry
- [ ] reconnect
- [ ] Speed
- [ ] RPM
- [ ] Coolant
- [ ] ECU Voltage

## Phase 3 — Android
- [ ] Create Android Studio project on Windows
- [ ] Kotlin + Jetpack Compose foundation
- [ ] BLE scan/connect
- [ ] OBD transport
- [ ] shared PID decoder parity
- [ ] Dashboard

## Phase 4 — Shared vehicle profiles
- [ ] universal OBD-II
- [ ] profile schema
- [ ] Toyota/Lexus
- [ ] Hyundai/Kia
- [ ] BMW（已查證：使用者貼的論壇 DID 表對不上 OBDb 真實擷取資料，不可信；OBDb 組織有近 30 個 BMW 車型 repo 但多數無資料，且用 functional addressing（hdr/rax/eax），需先擴充定址架構才能用，詳見 shared/protocol-docs/vehicle-profile-schema.md 第六輪）
- [ ] Volkswagen

## Phase 5 — EV / Hybrid
- [x] Mode 22（`shared/vehicle-profiles/citroen-ev.json`，Citroën/Peugeot 純電 EMP2 平台，取自 OVMS v3 `vehicle_fiatedoblo` 模組原始碼，詳見 vehicle-profile-schema.md 第十四輪）
- [ ] SOC（OVMS 原始碼本身標註「未使用，因為改用 CAN frame 0x3a8 廣播的值」，Mode 22 DID `0xd410` 只是校正值非主要來源，暫不收錄）
- [x] HV voltage（`citroen-ev.json`：`evBatteryVoltageV`，僅 Citroën/Peugeot EV）
- [x] HV current（`citroen-ev.json`：`evBatteryCurrentA`，僅 Citroën/Peugeot EV）
- [ ] battery power（衍生值＝電壓×電流，不是單一 DID，尚未實作）
- [x] battery temperature（`citroen-ev.json`：`evBatteryTempC`，僅 Citroën/Peugeot EV）
- [x] SOH（`citroen-ev.json`：`evBatterySohPercent`，僅 Citroën/Peugeot EV）

## Phase 6 — Product features
- [ ] CSV logging
- [ ] trip sessions
- [ ] charts
- [ ] GPS/map
- [ ] CarPlay feasibility for iOS
- [ ] Android Auto feasibility for Android
