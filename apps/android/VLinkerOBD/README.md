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
