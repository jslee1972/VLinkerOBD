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
- [ ] Mode 22
- [ ] SOC
- [ ] HV voltage
- [ ] HV current
- [ ] battery power
- [ ] battery temperature
- [ ] SOH

## Phase 6 — Product features
- [ ] CSV logging
- [ ] trip sessions
- [ ] charts
- [ ] GPS/map
- [ ] CarPlay feasibility for iOS
- [ ] Android Auto feasibility for Android
