# Android App

此資料夾是 Android 版本，主要在 Windows + Android Studio + Codex 開發。

技術方向：
- Kotlin
- Jetpack Compose
- Android BLE APIs
- minSdk 26+

第一階段目標：
1. 掃描 vLinker BLE 裝置
2. 連線與探索 GATT
3. 自動判斷 Write / Notify characteristic
4. ELM/STN 初始化
5. 輪詢 `010C`／`010D`，以動態指針儀表板顯示轉速／車速（見 `docs/2026-09-06-phase-1b-dashboard-gauges-and-brand-pids.md`）
6. 可選擇 Mazda／Ford／Honda 私有 PID 疊加顯示
7. 讀取目前故障碼（Mode 03，P/C/B/U 分類解碼）
8. 連線初始化後自動讀取 VIN（Mode 09）辨識車款，若有對應私有 PID 則自動切換
9. 辨識車款後自動讀取故障碼，有故障碼會顯示可點擊的警示卡片，點擊後彈窗顯示各代碼的分類與中文說明

請遵守 repo 根目錄的 `AGENTS.md`。

## 建置與測試

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

## 實機驗收清單

Phase 1 原始清單之外，本輪新增：

- [ ] 車速／轉速指針錶隨真實數據平滑轉動
- [ ] 手動送出不支援的 PID（如錯誤 mode）時畫面顯示對應的 `NO DATA`／`7F` 中文訊息，不當機
- [ ] 連續多次讀不到車速/轉速時，數值歸零顯示 `--`，之後恢復正常讀值
- [ ] 切換廠牌下拉選單（Mazda／Ford／Honda）後，慢速 ticker 開始輪詢對應私有 PID 且不影響車速/轉速的即時性
- [ ] 若手邊有 Mazda/Ford/Honda 對應車款，比對私有 PID 顯示的數值是否合理（這些資料標記 `community`/`forum-partial`，未在實車上驗證過）
- [ ] 「讀取故障碼」在已連線時可按，讀取中會停用；有故障燈亮起的車輛比對顯示的代碼（如 P0xxx）是否與診斷儀讀到的一致；無故障碼的車輛顯示「無故障碼」而非空白或錯誤
- [ ] 連線初始化完成後，畫面自動顯示「偵測到車款：X（VIN ...）」；若該廠牌有私有 PID profile（Mazda/Ford/Honda），廠牌下拉選單應自動切過去；VIN 讀不到的車輛不應卡住輪詢
- [ ] 有故障燈亮起的車輛，連線後應自動跳出可點擊的故障碼警示卡片（不用按按鈕）；點擊後彈窗列出每個代碼的分類與說明；無故障碼的車輛安靜顯示「無故障碼」，不跳警示
- [ ] 若讀到的是 Ford/Mazda/Honda 私有碼（P1xxx），詳情應顯示該廠牌的英文說明（標註尚無中文翻譯），而不是「尚無內建說明」
