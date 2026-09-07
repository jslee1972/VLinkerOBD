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

### 2026-09-07（第三輪：[iternio/ev-obd-pids](https://github.com/iternio/ev-obd-pids)）

ABRP（A Better Route Planner）官方維護、Apache-2.0，真實存在且格式一致（46 星）。收錄 Ford Mach-E、Honda e:Ny1、HKMC（Hyundai/Kia/Mitsubishi 共用平台 EV，追溯到同一個 JejuSoul 來源）、GMC、Jaguar、Mini、MG、Renault、Aiways、DeepAl，皆為主動 request/response（走 UDS Mode 22），**不是**前面提醒的被動監聽格式，資料取得管道正確。**這次先只記錄查證結果，不建立 profile**，原因：

1. **公式語言比目前的 `bitField` 更複雜**：他們用自己的運算式（`Signed()`、`Int16(A,B)`、`Int24(A,B,C)`、位元位移 `<<`/`>>`、單一位元旗標 `{A:#}`、邏輯運算 `\|\|`/`&&`/`!`/比較運算子），變數命名可到 `A`–`ZZZ`（超過 26 個位元組时用雙字母，如 `af`、`am`）。抽查 `hkmc/hkmc2019.json`：`current`/`voltage`/`soc`/`soh`/`batt_temp`/`ext_temp`/`kwh_charged` 這些數值型欄位都能人工換算成現有 `bitField`（單一連續位元段 + signed + 除數），但 `is_charging`（`(!{ay:2}&&{ay:3})`，同時比較兩個不同位元再做邏輯運算）目前的 `bitField` 規格做不到，需要擴充成「多位元欄位 + 布林運算」才能表示。
2. **初始化序列衝突，是比 Mazda/Ford/Honda 更大的架構改動**：HKMC 資料的 `init_commands` 用 `ATH1`（header 開啟）+ `ATSP6`（強制協定），跟本專案目前全域固定的 `ATH0`（header 關閉）+ `ATSP0`（自動偵測）相反。現有的 `ecuHeader`/`ecuReceiveFilter` 機制只處理「查詢私有 PID 前後臨時切換」，前提是全域 header/protocol 設定不變；要支援 HKMC 需要讓「選擇廠牌」能觸發重新初始化、套用該廠牌自己的一套全域 AT 設定，這是新的架構能力，不是現有 `restartBrandPolling()` 的擴充範圍。理論上 `ObdResponseParser` 的 marker 搜尋不要求從資料開頭比對，`ATH1` 多出來的 header 前綴應該不影響現有標準 PID 解析，但**沒有實體車或模擬器驗證過**。

若之後要做 Hyundai/Kia EV 支援，這則記錄可以直接當清單用：先解決「per-brand 全域 AT 設定」架構、需要時再擴充 `bitField` 支援多位元布林運算、`is_charging` 這類欄位可以先跳過。

### 2026-09-07（第四輪：[prototux/PSA-RE](https://github.com/prototux/PSA-RE)）

真實存在、活躍維護（Apache-2.0、100 星、近期更新），逆向 PSA（Peugeot/Citroën/DS）AEE2001/2004/2010 車身電子架構。抽查 `buses/AEE2004.full/HS.IS/412.yml`：`periodicity: 50`、`senders`/`receivers` 欄位，內容是車門/手煞車/機油溫度等訊號——確認是**被動監聽 VAN/CAN 車身舒適網路週期性廣播封包**，跟 OVMS/opendbc/i-miev-obd2 同一類，不是 OBD-II request/response。專案自己的 README 也明白列出「Document the diagnostics (KWP/UDS) part」仍是待辦（作者亦已聲明不再積極維護），代表連原作者都還沒逆向出診斷層。暫不建立 profile。

Citroën/Peugeot 目前已連續四輪查證（`autowp/psa-can` 不存在、OVMS 被動監聽、`psa-dpf-monitor-pids` 等不存在、PSA-RE 被動監聽），皆未找到可用的 request/response 診斷層資料，建議除非有新的具體來源，否則暫時擱置這個廠牌。

### 2026-09-07（第五輪：[nico1080/OBD-LCD-display-for-PSA](https://github.com/nico1080/OBD-LCD-display-for-PSA)、[flobz/psa_car_controller](https://github.com/flobz/psa_car_controller)）—— 推翻上一輪的擱置建議

`flobz/psa_car_controller`：真實存在（579 星），但是 PSA 官方連網服務（`connected_car v4 API`）的第三方遠端控制工具，走網路呼叫 PSA 伺服器（車鎖、空調、電量），需要車主 PSA 帳號登入，完全不經過 OBD-II 診斷埠，跟本專案的存取方式無關，不採用。

`nico1080/OBD-LCD-display-for-PSA`：真實存在（Arduino + MCP2515 CAN 模組專案，18 星）。**這次是真的可用的資料源**——原始碼 `CanRequest(TxID, RxID, DID高位, DID低位, 長度)` 送出的封包是 `03 22 <DID_hi> <DID_lo>`，正是標準 **UDS Mode 22（ReadDataByIdentifier）** 單幀請求，回應 `62 <DID回音> <資料>`，跟本專案 Ford/Honda 現有的存取方式完全相同（不是被動監聽）。已從原始碼整理出引擎（header `6A8`/`688`）、胎壓 TPMS（`6AF`/`68F`）、車身 BSI（`752`/`652`）三個 ECU 共 24 個 PID，公式都是單/雙位元組四則運算，建了 `citroen.json`（`verified: "forum-partial"`，比照 Mazda 等級——單一作者在自己的 2016 Citroën DS4，EP6FDTX 引擎＋BSI2010 車身電腦上實測，非社群多車驗證）。MainActivity 把這份 profile 同時掛在「Citroen」與「Peugeot」兩個偵測到的廠牌名稱下（PSA 集團同引擎/車身電腦世代常見共用 DID，但只有 Citroën DS4 這一台車實測過）。

**教訓**：同一個廠牌不同輪查證可能得到完全相反的結論——本輪能找到可用資料，純粹是因為這個作者用的是主動 UDS request/response，而不是前四輪那些被動監聽 CAN bus 的專案。之後查其他還沒查到資料的廠牌，除了看資料存不存在，更要先確認是不是走這條「主動送指令、等回應」的路。
