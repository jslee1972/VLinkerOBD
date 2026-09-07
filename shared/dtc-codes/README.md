# DTC (故障碼) 說明資料庫

用來把 Mode 03/07/0A 讀到的故障碼（如 `P0133`、`U0121`）轉成人看得懂的說明。

## 來源

資料取自 [Wal33D/dtc-database](https://github.com/Wal33D/dtc-database)（MIT License），該專案整理了 SAE J2012 通用碼與 33+ 廠牌的私有碼，共 18,805 筆，經抽查（`P0420`、`P0133`、`U0121` 等）比對確認與標準定義一致。

- `generic.json`：SAE J2012 通用碼（`is_generic=1`），9,415 筆，任何車廠意義相同。
- `ford.json` / `mazda.json` / `honda.json`：對應廠牌私有碼（P1xxx 等），分別 413／233／94 筆——原本只抽取這三個廠牌，因為當時只有這三家有私有 PID profile（見 `shared/vehicle-profiles/`）。
- `bmw.json`：BMW 私有碼，317 筆（Wal33D 的 250 筆 P1xxx ＋ 手動從使用者提供的 BMW 原廠維修手冊（Bentley Publishers 授權版）掃描頁轉錄的 67 筆 P3xxx，見下方「BMW 手冊轉錄」）。BMW 目前**沒有**私有 PID profile（見 `vehicle-profile-schema.md` 第六～八輪查證，定址方式未解決），但 DTC 描述是純顯示層查表、不需要 PID profile 就能用——已把 `DashboardScreen.kt` 的故障碼詳情對話框改成優先用 `selectedBrand`、沒有時退回 `detectedBrand`（VIN 自動判斷），這樣才會實際用到這份資料。
- `toyota.json` / `mercedes-benz.json` / `volkswagen.json` / `kia.json` / `mitsubishi.json`：對應廠牌私有碼，分別 45／32／528／76／34 筆。跟 bmw.json 一樣目前都**沒有**私有 PID profile，只提供故障碼說明。

原始資料庫涵蓋 33 個廠牌，但**沒有 Peugeot / Citroën（PSA）、Audi、Hyundai**——與先前查證 PID 資料時的結論一致，這幾家如果要故障碼說明需要另找來源。

### BMW 手冊轉錄（P3xxx）

抽取 Wal33D 的 BMW 子集時發現只有 P1xxx（250 筆），使用者提供的手冊裡另外還有 P2xxx（DMTL 蒸發系統）跟 P3xxx（高壓噴油嘴，柴油引擎專屬）。逐筆核對後：
- **P2xxx 全部跳過**：抽查後發現這個範圍不是廠牌私有碼，跟 `generic.json` 完全重複（BMW 只是用自己的內部術語如 DMTL 描述同一組 SAE 通用碼），加了也是重複資料。
- **P3xxx 全部是真的私有碼**（P3xxx 是 SAE 保留的廠牌自訂區間，跟 P1xxx 一樣，確認不在 `generic.json` 裡），視覺辨識手冊掃描頁轉錄了 67 筆，已加入 `bmw.json`。這批資料**沒有第二來源交叉驗證**（跟 P1xxx 不同，Wal33D 沒有涵蓋 P3xxx），純粹依賴手冊掃描頁的視覺辨識，使用者已同意承擔這個風險。轉錄時跳過了少數幾筆手冊上出現疑似重複/矛盾格式的列（例如同一個 P-code 對應到不一致的 cylinder 編號，或代碼編號脫離該頁數字順序），不確定的寧可不收錄，不用猜的湊數。

## 語言

原始資料是英文。App 的 `DtcDescriptions` 會優先用內建的繁體中文翻譯（涵蓋約 60 個最常見的通用碼，抽查已與此資料庫比對一致）；查不到中文翻譯但這個資料庫裡有的碼，會顯示英文原文並標註「英文原文，尚無中文翻譯」，不會为了語言一致而編造翻譯。

## Android 端使用注意

同步一份到 `apps/android/VLinkerOBD/app/src/main/assets/dtc-codes/`。更新本目錄 JSON 後記得同步複製。
