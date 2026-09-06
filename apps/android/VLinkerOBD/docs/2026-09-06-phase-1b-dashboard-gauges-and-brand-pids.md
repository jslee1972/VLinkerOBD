# Phase 1b：動態儀表板、廠牌 PID、NO DATA/7F 處理

> 附錄文件，補充 `2026-08-28-phase-1-ble-obd-design.md`，不覆寫原文。原 Phase 1 設計假設「單一 `010D` 輪詢＋純文字車速」；本輪需求擴充為雙 PID 輪詢、指針儀表板、可選廠牌私有 PID，記錄於此。

## 輪詢時序調整

原設計每 200 ms 輪詢一次 `010D`。現在改為每一輪依序輪詢 `010C`（轉速）與 `010D`（車速）兩個命令，兩者皆完成後才 `delay(200ms)` 進入下一輪（見 `DashboardViewModel.startPolling`）。command queue 仍然保持「同時只有一個命令在途」的限制不變。

## NO DATA / 7F 狀態機

`ObdResponseParser.classify()` 回傳 `ObdResponseStatus`：`Data`、`NoData`、`NegativeResponse(service, nrc, messageZh)`、`Unrecognized`。`DashboardViewModel.pollOnce()` 遇到 `NoData`/`NegativeResponse` 不會清空目前讀數，只寫 log；車速/轉速個別累計「連續失敗次數」，達到 5 次才把畫面歸零顯示 `--`（見 `STALE_THRESHOLD`）。

## 公式求值器與 bit 欄位

新增 `PidFormula`（A/B/C/D 四則運算，涵蓋標準 Mode 01 與 Mazda 胎壓 PID）與 `BitFieldExtractor`（bit 位置＋長度＋除數，涵蓋 Ford/Honda 這類把多個訊號打包在同一個長 UDS 回應裡的私有 PID）。`PidDefinition` 依資料形狀二選一（`formula` 或 `bitField`），詳見 `shared/protocol-docs/vehicle-profile-schema.md`（v3）。

## 廠牌 PID 輪詢

選擇 Mazda／Ford／Honda 等廠牌 profile 後，`DashboardViewModel.restartBrandPolling()` 會用獨立的慢速 ticker（每個 PID 之間間隔 3 秒）輪詢，需要 `ecuHeader`/`ecuReceiveFilter` 時先送 `ATSH<header>`／`ATCRA<filter>`，查完送 `ATCRA`／`ATSH00` 還原，過程中會暫停快速的車速/轉速輪詢（共用 `fastLoopPaused` 旗標），避免搶佔或互相干擾。

## 故障碼讀取（Mode 03）

新增 `DtcParser`，依 SAE J2012／ISO 15031-6 解碼 Mode 03（目前故障碼，之後可比照擴充 07 待定碼／0A 永久碼）：回應第一byte（`43`）之後每 2 byte 一組，第一 byte 高 2 bit 決定 `P`/`C`/`B`/`U` 分類，其餘 14 bit 轉成 4 位十六進位數字；`00 00` 視為填充略過。`DashboardViewModel.readTroubleCodes()` 送出 `03`、暫停快速輪詢（沿用 `fastLoopPaused`），解析結果存進 `DashboardUiState.troubleCodes`（`null`＝尚未查詢，空清單＝已查詢但無故障碼）。畫面上「讀取故障碼」按鈕只在已連線（`isReady`）時顯示。

尚未實作：Mode 04 清除故障碼（原設計文件即提醒清碼會同時熄滅故障燈、重置學習值，屬於需要另外確認的破壞性操作，之後有需要再加）、Mode 07／0A 的 UI 入口（`DtcParser` 已支援，只是 ViewModel 目前只呼叫 03）。

## Gauge

`ui/gauge/Gauge.kt` 用 Compose Canvas 自繪指針錶（不依賴外部 gauge 套件），角度換算抽成純函式 `GaugeMath.valueToAngleDegrees` 方便單元測試。車速錶 0–220 km/h，轉速錶 0–8000 rpm、6500 以上紅線。

## 已知限制

- 未在實體 vLinker 裝置／實車上驗證過 BLE 連線、真正的 `NO DATA`/`7F` 回應、Ford/Honda/Mazda 私有 PID 是否真的回應正確數值——這些資料標記為 `verified: "community"` 或 `"forum-partial"`，來源見 schema 文件，仍需人工在真車上比對。
- `bitField` 的 bit 編號慣例（MSB-first、從 payload 第 0 個 bit 算起）是依 OBDb 資料本身規律推斷，非官方規格。
