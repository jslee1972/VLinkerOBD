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
5. 輪詢 `010D` 並顯示車速

請遵守 repo 根目錄的 `AGENTS.md`。
