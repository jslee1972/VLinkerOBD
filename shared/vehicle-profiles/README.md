# Vehicle Profiles

跨平台共用的車型 / PID 定義放在這裡。

建議使用 JSON/YAML 等資料格式，而不是把品牌專用 PID hard-code 在 iOS 或 Windows UI。

Schema 定義見 `shared/protocol-docs/vehicle-profile-schema.md`（v3）。

## 目前檔案

- `universal-obd2.json`：標準 Mode 01 PID，任何車輛都適用，永遠載入。
- `mazda.json`：Mazda 私有胎壓 PID（Mode 22），依 `models` 分 Miata NC / RX-8 / MazdaSpeed6，各自需要不同的 `ecuHeader`。僅在論壇個案驗證過，`verified: "forum-partial"`。
- `ford.json`：Ford Fiesta 里程、胎壓 x4、胎壓警示燈，取自 [OBDb](https://github.com/OBDb) 社群維護資料集，`verified: "community"`，用位元層級 `bitField` 描述（非 `formula`）。
- `honda.json`：Honda Civic Hybrid SOC、高壓電池電壓/電流、水溫 x2、發電機占空比、里程，同樣取自 OBDb，`verified: "community"`，`bitField` 格式。

## 載入方式

App 永遠載入 `universal-obd2.json`，使用者可另外選擇一個 brand profile（`mazda.json`／`ford.json`／`honda.json`）疊加顯示。其餘廠牌（Toyota、Lexus、Hyundai、Kia、BMW、Volkswagen、Citroën、Peugeot、Mercedes-Benz、Audi、Mitsubishi 等）目前查無可信賴、且存取方式相容（見下方「架構提醒」）的公開私有 PID 資料，調查記錄見 schema 文件，暫不建立對應檔案。

## Android 端使用注意

Android 專案無法直接引用 repo 外路徑，`shared/vehicle-profiles/*.json` 需要手動同步一份到 `apps/android/VLinkerOBD/app/src/main/assets/vehicle-profiles/`。修改本目錄下的 JSON 後，記得同步複製。
