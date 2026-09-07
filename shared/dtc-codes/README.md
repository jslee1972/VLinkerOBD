# DTC (故障碼) 說明資料庫

用來把 Mode 03/07/0A 讀到的故障碼（如 `P0133`、`U0121`）轉成人看得懂的說明。

## 來源

資料取自 [Wal33D/dtc-database](https://github.com/Wal33D/dtc-database)（MIT License），該專案整理了 SAE J2012 通用碼與 33+ 廠牌的私有碼，共 18,805 筆，經抽查（`P0420`、`P0133`、`U0121` 等）比對確認與標準定義一致。

- `generic.json`：SAE J2012 通用碼（`is_generic=1`），9,415 筆，任何車廠意義相同。
- `ford.json` / `mazda.json` / `honda.json`：對應廠牌私有碼（P1xxx 等），分別 413／233／94 筆——只抽取這三個廠牌，因為目前只有這三家有私有 PID profile（見 `shared/vehicle-profiles/`）。

原始資料庫涵蓋 33 個廠牌（含 BMW、Audi、VW、Mercedes、Toyota、Hyundai、Kia、Mitsubishi 等），但**沒有 Peugeot / Citroën（PSA）**——與先前查證 PID 資料時的結論一致。如果之後要幫其他廠牌（例如 BMW、Toyota）也做故障碼詳情，可以直接從同一個資料庫再抽取，不需要重新查證。

## 語言

原始資料是英文。App 的 `DtcDescriptions` 會優先用內建的繁體中文翻譯（涵蓋約 60 個最常見的通用碼，抽查已與此資料庫比對一致）；查不到中文翻譯但這個資料庫裡有的碼，會顯示英文原文並標註「英文原文，尚無中文翻譯」，不會为了語言一致而編造翻譯。

## Android 端使用注意

同步一份到 `apps/android/VLinkerOBD/app/src/main/assets/dtc-codes/`。更新本目錄 JSON 後記得同步複製。
