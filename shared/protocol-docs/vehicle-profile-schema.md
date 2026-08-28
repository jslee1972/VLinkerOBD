# Vehicle Profile Schema v1

跨 iOS / Android 共用的 vehicle profile 應使用資料檔描述 PID，而非 hard-code 在 UI。

最小欄位：
- `schemaVersion`
- `profileId`
- `displayName`
- `request`
- `field`
- `unit`
- `formula`

後續可增加：
- ECU address / header
- protocol
- response prefix
- minimum polling interval
- availability condition
- brand/model/year matching
- Mode 22 / UDS metadata

兩個 App 應將同一 profile 解碼成一致的 VehicleData 欄位名稱。
