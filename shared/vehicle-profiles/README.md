# Vehicle Profiles

跨平台共用的車型 / PID 定義放在這裡。

建議使用 JSON/YAML 等資料格式，而不是把品牌專用 PID hard-code 在 iOS 或 Windows UI。

Schema 定義見 `shared/protocol-docs/vehicle-profile-schema.md`（v3）。

## 目前檔案

- `universal-obd2.json`：標準 Mode 01 PID，任何車輛都適用，永遠載入。除核心 PID 外，也包含一批柴油/排放相關的 SAE J1979 延伸 PID（增壓、DPF 進/出氣壓力、燃油軌壓、排氣溫、NOx 等）跟一批補充的常見延伸 PID（絕對負載、指令當量比、相對節氣門/踏板位置、乙醇燃料%、燃油軌絕對壓力、引擎扭矩 x3）——這些都對照官方 J1979-DA 規格書逐條驗證過，車輛需有對應硬體才會回應有意義的數值，詳見 schema 文件第七、十一輪查證記錄。規格書裡另外定義的 `$03`（燃油系統狀態）、`$51`（燃料種類）是列舉狀態值不是數字，`$64`（引擎扭矩曲線圖）是多點曲線圖不是單一數值，目前公式引擎只支援純數值 PID，這三個先跳過。
- `mazda.json`：Mazda 私有胎壓 PID（Mode 22），依 `models` 分 Miata NC / RX-8 / MazdaSpeed6，各自需要不同的 `ecuHeader`。僅在論壇個案驗證過，`verified: "forum-partial"`。
- `ford.json`：Ford Fiesta 里程、胎壓 x4、胎壓警示燈，取自 [OBDb](https://github.com/OBDb) 社群維護資料集，`verified: "community"`，用位元層級 `bitField` 描述（非 `formula`）。
- `honda.json`：Honda Civic Hybrid SOC、高壓電池電壓/電流、水溫 x2、發電機占空比、里程，同樣取自 OBDb，`verified: "community"`，`bitField` 格式。
- `citroen.json`：Citroën/Peugeot (PSA) 引擎渦輪/機油壓力、四輪胎壓/胎溫、車身電瓶/油量/剩餘里程，取自 [nico1080/OBD-LCD-display-for-PSA](https://github.com/nico1080/OBD-LCD-display-for-PSA)，`verified: "forum-partial"`——單一作者在 2016 Citroën DS4（EP6FDTX 引擎/BSI2010）實測，非社群多車驗證；App 裡同時對應「Citroen」與「Peugeot」兩個偵測到的廠牌。原始來源另外還有 rpm/outside temp/engine oil temp/fuel level 四個 Mode 22 DID，因為跟 `universal-obd2.json` 的標準 Mode 01 PID 完全重複（同一件事、多切一次 ECU header），已移除，見 schema 文件第十二輪。
- `citroen-ev.json`：Citroën/Peugeot 純電 EMP2 平台（ë-Berlingo/e-Rifter/Combo-e/ProAce City，約 2022 年後）電池管理系統/VCU 專屬 PID（電池電壓/電流/SOH/可用電量/最小最大單體電壓、環境/電池/DC-DC/車載充電器溫度），取自 [OVMS v3](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3) 的 `vehicle_fiatedoblo` 模組原始碼，逐行比對解碼邏輯重新推導公式，`verified: "community"`。這是**電動車電池系統專屬資料，燃油版車輛不會有對應數值**；`VLinkerObdApplication` 會把它跟 `citroen.json` 合併成同一個 Citroen/Peugeot profile（不額外做 EV/ICE 車型選擇），燃油車遇到這些 PID 會 NO DATA 並自動退避，跟其他不支援的 PID 一視同仁，見 schema 文件第十四輪。

## 載入方式

App 永遠載入 `universal-obd2.json`，使用者可另外選擇一個 brand profile（`mazda.json`／`ford.json`／`honda.json`／`citroen.json`，Citroen/Peugeot 會再疊加 `citroen-ev.json`）疊加顯示。其餘廠牌（Toyota、Lexus、Hyundai、Kia、BMW、Volkswagen、Mercedes-Benz、Audi、Mitsubishi 等）目前查無可信賴、且存取方式相容（見下方「架構提醒」）的公開私有 PID 資料，調查記錄見 schema 文件，暫不建立對應檔案。

## Android 端使用注意

Android 專案無法直接引用 repo 外路徑，`shared/vehicle-profiles/*.json` 需要手動同步一份到 `apps/android/VLinkerOBD/app/src/main/assets/vehicle-profiles/`。修改本目錄下的 JSON 後，記得同步複製。
