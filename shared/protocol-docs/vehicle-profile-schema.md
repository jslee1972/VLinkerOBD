# Vehicle Profile Schema v3

跨 iOS / Android 共用的 vehicle profile 應使用資料檔描述 PID，而非 hard-code 在 UI。

## v1 最小欄位（沿用）

- `schemaVersion`
- `profileId`
- `displayName`
- `request`
- `field`
- `unit`
- `formula`

## v2 欄位

- `brand`（選填）：廠牌名稱，例如 `"Mazda"`。`universal-obd2.json` 不填此欄位，代表任何車輛都適用；有填的檔案（如 `mazda.json`）代表該廠牌／車型專屬的私有 PID 群組。
- `displayNameZh`（選填）：PID 群組在 UI 上顯示的繁體中文名稱。
- 每個 PID 項目可選填：
  - `ecuHeader`：送出此 PID 前需要先送 `ATSH<ecuHeader>` 切換 CAN header。沒有此欄位代表用預設 header（`ATSP0` 自動偵測後的引擎 ECU）即可查詢。可以放在 PID 項目本身，也可以放在 `models` 底下的 model 層級當作該 model 全部 PID 的預設值（PID 層級的設定優先）。
  - `verified`：資料可信度註記——`"verified"`（原廠文件或多方獨立來源互相印證）、`"community"`（社群維護、格式一致且經多車實測回報的結構化資料庫，例如下方的 OBDb）、`"forum-partial"`（僅有零星論壇貼文佐證，尚未廣泛驗證）。UI 對非 `verified` 等級的資料應提示使用者「未完全驗證」。

## v3 新增欄位：`ecuReceiveFilter` 與 `bitField`

- `ecuReceiveFilter`（選填）：查詢多 ECU 掛在同一 CAN bus 時，用來過濾只接受特定 ECU 回應的位址（對應 ELM327 `ATCRA<ecuReceiveFilter>`），在送出 `ecuHeader`/`ATSH` 之後、實際查詢指令之前送出；查完要送 `ATCRA`（不帶參數）或 `ATAR` 清除濾波器，恢復自動接收位址。沒有此欄位代表不需要設定濾波器。
- `bitField`（選填，取代 `formula`）：當一個 PID 的回應把很多訊號打包在同一個很長的 UDS payload 裡（例如電池模組逐一單體電壓、多個溫度感測器共用一個 22 指令），用位元層級描述會比一個「A/B/C/D 四則運算」formula 字串精確：
  ```json
  {
    "bitIndex": 1608,
    "bitLength": 16,
    "multiplier": 1.0,
    "divisor": 10.0,
    "offset": -40.0,
    "signed": true,
    "min": -40.0,
    "max": 215.0
  }
  ```
  - `bitIndex`：從 0 開始，指 mode+PID echo 之後 payload 的第幾個 bit（bit 0 = payload 第一個 byte 的最高位元，MSB-first、跨 byte 連續編號——即社群慣用的「Motorola / big-endian」bit 編號法）。
  - `bitLength`：要讀取的 bit 數。
  - `multiplier`／`divisor`／`offset`：`值 = 原始整數 * multiplier / divisor + offset`，三者皆選填，預設 `1`／`1`／`0`。
  - `signed`：選填，預設 `false`；`true` 時用二補數解出負值。
  - `min`／`max`：選填，超出範圍會被夾住（clamp）。
  - 一個 PID 只會有 `formula` 或 `bitField` 其中一種，不會兩者都填。

  **bit 編號慣例的重要提醒**：`bitIndex`/MSB-first 的解讀是依照 [OBDb](https://github.com/OBDb) 專案（見下方調查記錄）資料本身的排列規律（連續訊號的 `bitIndex` 剛好以 `bitLength` 為間距遞增）推斷出來的慣例，OBDb 專案本身未找到正式規格文件明確寫出這個約定；在真正的實體車上驗證數值合理性之前，應視為「高信心度推斷」而非官方保證。

Android 端由 `BitFieldExtractor`（`apps/android/VLinkerOBD/app/src/main/java/com/jslee1972/vlinkerobd/obd/BitFieldExtractor.kt`）實作上述解碼，`PidFormula` 則維持只處理 `A/B/C/D` 四則運算字串（兩者互不取代，一個 PID 定義依資料形狀選一種）。

## 同廠牌多車型／多指令 header 差異

同一廠牌底下不同車型、甚至同一車型不同 PID，都可能需要不同的 `ecuHeader`（例如 Mazda 胎壓 PID，Miata NC 與 RX-8/MazdaSpeed6 的 header 不同；Ford Fiesta 里程數與胎壓分別要切換到不同 ECU header）。有「多車型」差異時用 `models` 陣列分組，每個 model 有自己的 `modelId`、`displayNameZh`、`ecuHeader`（當作底下 PID 沒指定時的預設值），底下才是 `pids` 清單：

```json
{
  "schemaVersion": 3,
  "profileId": "mazda",
  "brand": "Mazda",
  "models": [
    {
      "modelId": "miata-nc",
      "displayNameZh": "Miata (MX-5 NC)",
      "ecuHeader": "720",
      "pids": [ ... ]
    }
  ]
}
```

只有「同一指令集但每個 PID 各自需要不同 header」（沒有車型分支）時，不需要 `models`，直接在頂層 `pids`裡每個 PID 各自帶自己的 `ecuHeader`/`ecuReceiveFilter`（比照下方 `ford.json`、`honda.json`）。

## Profile 載入慣例

- `universal-obd2.json` 永遠載入。
- 使用者可另外選擇一個 `brand` profile 疊加在 universal 之上，疊加的 PID 會額外顯示，不影響車速/轉速等通用讀數的輪詢。
- 尚未有可信公開資料的廠牌先不建立對應 JSON 檔，UI 上顯示「尚無可靠公開資料」。

## 廠牌私有 PID 調查記錄

### 2026-09-06（第一輪：Mazda / Hyundai-Kia EV / Toyota / BMW / VW）

| 廠牌 | 結論 | 來源 |
|---|---|---|
| Mazda | 胎壓 `22 C9 01~04`，公式 `((X*1373)/1000)*0.145037738` psi，需要先送 `ATSH720`（Miata NC）或 `ATSH751`（RX-8/MazdaSpeed6）。已建立 `mazda.json`，標記 `verified: "forum-partial"`。 | [mazdaspeeds.org](https://www.mazdaspeeds.org/threads/torque-tire-pressure-and-temp-pids.9932/)、[rx8club.com](https://www.rx8club.com/series-i-tech-garage-22/validated-torque-pids-250627/) |
| Hyundai / Kia (EV) | 有完整公開資料集，格式跟下方 OBDb 類似（位元層級、需要 signed）。`bitField` 求值器上線後技術上可行，但 ECU header/receive-filter 尚未查證，這輪不建立 profile。 | [JejuSoul/OBD-PIDs-for-HKMC-EVs](https://github.com/JejuSoul/OBD-PIDs-for-HKMC-EVs) |
| Toyota | Mode 21 為 Toyota 專屬 service，但公開資料零散、無法互相驗證，暫不建立 profile。 | [ToyotaNation 論壇](https://www.toyotanation.com/threads/toyota-obd2-pid-codes.1028530/) 等，僅供未來人工查證 |
| BMW | Mode 22 資料多屬付費資料庫（Equipment and Tool Institute），公開找到的少數 DID（如 `22F190` 讀 VIN）屬通用 UDS 識別碼，非 BMW 專屬，暫不建立 profile。 | [Autosport Labs 論壇](https://forum.autosportlabs.com/viewtopic.php?t=6576) |
| Volkswagen / Audi | 找到的 Mode 22 PID（如 `22114F` 煤灰質量）多為柴油 DPF 相關單一論壇分享，未互相驗證，暫不建立 profile。 | [t6forum.com](https://www.t6forum.com/threads/vw-t6-custom-pid-codes-for-dpf.33964/) |

### 2026-09-06（第二輪：Citroën/Peugeot、Mercedes-Benz、Audi、Ford、Honda、Mitsubishi）

發現 [OBDb](https://github.com/OBDb) 專案：依廠牌／車型分 repo（如 `Ford-Fiesta`、`Honda-Civic`），每個 repo 有 `signalsets/v3/default.json`，用上面 `bitField` 對應的位元層級格式描述訊號，由社群多車實測維護，是本輪唯一格式一致、可信度足夠採用的資料源，標記 `verified: "community"`。

| 廠牌 | 結論 | 來源 |
|---|---|---|
| Ford | OBDb 有 `Ford-Fiesta` 完整資料。已建立 `ford.json`：里程 `22 404C`（header `720`/receive-filter `728`）、四輪胎壓 `22 2813~2816`（header `726`/receive-filter `72E`）、胎壓警示燈 `22 61A5`。 | [OBDb/Ford-Fiesta](https://github.com/OBDb/Ford-Fiesta) |
| Honda | OBDb 有 `Honda-Civic` 完整資料（含 Hybrid 電池模組，多達 84 顆單體電壓，超出一般儀表板需求）。已建立 `honda.json`，只取代表性子集：SOC、高壓電池電壓/電流、四個水溫感測器、發電機占空比、里程。 | [OBDb/Honda-Civic](https://github.com/OBDb/Honda-Civic) |
| Audi | OBDb 有 `Audi-A3`、`Audi-A5` repo，但兩者 `signalsets/v3/default.json` 目前都是空的（`{"commands": []}`），尚無實際資料可用，暫不建立 profile，待 OBDb 社群補齊後再查。 | [OBDb/Audi-A3](https://github.com/OBDb/Audi-A3)、[OBDb/Audi-A5](https://github.com/OBDb/Audi-A5) |
| Citroën / Peugeot (PSA) | 使用者提供的 `autowp/psa-can` 經查證**不存在**（404）。OVMS（`openvehicles/Open-Vehicle-Monitoring-System-3`）確實支援 PSA 電動車，但其資料是給直接接 CAN bus 的裝置用的「被動監聽廣播封包」，不是我們這種透過 OBD-II 診斷埠「主動送 request 等 response」的存取方式，不能直接套用到目前的 `ObdCommandQueue` 架構。OBDb 目前無 PSA repo。暫不建立 profile。 | 已查證 autowp/psa-can 不存在；[OVMS](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3) 為被動 CAN 監聽架構 |
| Mercedes-Benz | 使用者提供的 `mercedes-benz/odxtools` 確實存在，但它是賓士官方開源的 ODX/PDX**檔案格式解析工具**，本身不含任何實際車輛 PID/DID 數據（賓士並未公開這些檔案）。`commaai/opendbc` 的 DBC 資料是給 ADAS 直連 CAN bus 用的廣播訊號，同樣不是 OBD-II request/response 存取方式。OBDb 目前無 Mercedes repo。暫不建立 profile。 | 已查證 odxtools 為解析工具而非資料 |
| Mitsubishi | 使用者提供的 `harshadura/libmut` 確實存在，但它是三菱專屬 **MUT-III（K-Line）診斷協定**的實作，跟本專案用的 ELM327/vLinker OBD-II AT 指令是完全不同的傳輸層，無法直接套用。`plaes/i-miev-obd2` 也確實存在且是 i-MiEV 專屬文件，但同樣記錄的是被動監聽的週期性 CAN 廣播訊息（如「412 - Speed + Odometer」），不是主動 request/response 的 PID。OBDb、iternio/ev-obd-pids 皆無 Mitsubishi repo。暫不建立 profile。 | 已查證 libmut 為 MUT-III 協定、i-miev-obd2 為被動廣播監聽格式 |

**重要架構提醒**：這輪查證釐清了一個常見誤區——很多開源車輛資料庫（OVMS、opendbc、i-miev-obd2 這類）記錄的是「直接接上 CAN bus、被動監聽 ECU 之間互相廣播的封包」，跟本專案透過 vLinker／ELM327 BLE 轉接器「主動送出 AT／OBD 指令、等待 `>` prompt 回應」的存取方式是兩種不同的資料取得管道，前者的資料不能直接搬進 `shared/vehicle-profiles/`。之後查其他廠牌時，要先確認資料是不是走 request/response（Mode 01/22 + 回應）這條路。
