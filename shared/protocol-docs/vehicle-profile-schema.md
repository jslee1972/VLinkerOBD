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

## v4 新增欄位：`fastPoll`

- `fastPoll`（選填，布林值，預設 `false`）：標記這個廠牌 PID 變化速度快（例如檔位、油門開度這類會隨駕駛動作即時變動的數值），需要比同一份 profile 裡其他 PID 更頻繁查詢。同一輪詢清單裡的所有 PID 原本共用同一個輪詢間隔，PID 數量一多（例如 `citroen.json` 有近 20 個），變化快的欄位反而要等一整輪跑完才會再問一次，可能長達一分鐘。標記 `fastPoll: true` 的 PID 會另外跑一個獨立、間隔短很多的輪詢迴圈（`DashboardViewModel.FAST_BRAND_POLL_INTERVAL_MS`），不受同一份 profile 裡其他慢速 PID 拖累。

## v5 新增欄位：`displayNameZh`／`descriptionZh`／`group`（per-PID）

跨平台的中文顯示名稱、參數說明、分組，原本各自寫死在 Android（`PidDisplayNames.kt`／`ParameterDescriptions.kt`／`ParameterGroups.kt`）與 iOS 對應檔案裡，兩邊要各自維護一份重複的對照表。v5 把這三者搬進 `shared/vehicle-profiles/*.json` 的 PID 定義本身，兩平台改成直接讀 JSON，不再各自寫一份：

- `displayNameZh`（選填）：這個 PID 在 UI 上顯示的簡短中文名稱（例如 `"水溫"`、`"胎壓 1"`）。跟 profile 層級/model 層級既有的 `displayNameZh`（分組/車型標題用）是不同用途，可以同名但語意不同。
- `descriptionZh`（選填）：較長的中文說明文字，用在「i」說明圖示彈窗，解釋這個數值代表什麼、正常範圍、為什麼值得關注。
- `group`（選填）：這個 PID 在「即時參數」畫面要歸類到哪一個分組（例如 `"引擎與動力"`、`"溫度"`、`"壓力"`、`"燃油與排放"`、`"電力與診斷"`、`"廠牌專屬"`）。分組**名稱字串**是資料（放在 JSON），但分組的**顯示順序**跟固定的分組常數清單仍然是各平台程式碼自己定義（這是版面配置，不是車輛資料）。目前所有廠牌 profile 的 PID 一律標 `"廠牌專屬"`。

三個欄位都選填，沒有填的 PID 由各平台 fallback：`displayNameZh` 缺省顯示 `field` 原始 camelCase 名稱、`descriptionZh` 缺省顯示「尚無詳細說明。」、`group` 缺省歸類到廠牌專屬分組——延續原本 Android 端「不編造內容，缺資料就誠實顯示 fallback」的規則。

**例外**：行車電腦衍生欄位（`instantFuelConsumption`／`averageFuelConsumption`／`acceleration`／`tripDistance`／`tripDuration`）不是 PID、不出現在任何 `shared/vehicle-profiles/*.json` 裡（它們是輪詢迴圈算出來的衍生值，沒有 `request`/`formula` 可言），這 5 個欄位的中文名稱/說明/分組仍然各自寫死在兩平台程式碼裡（一個小到不需要资料驅動的例外，內容跟遷移前一致）。

`speedKPH`／`rpm` 兩個核心欄位也刻意不填 `displayNameZh`／`descriptionZh`——UI 上用專屬儀表指針呈現，中文標籤（「車速」「轉速」）直接寫在儀表元件裡，不透過這個表。

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

### 2026-09-07（第六輪：使用者貼上一份 BMW Mode 22 DID 表，含 Engine Coolant Temp `22F105`、Engine Oil Temp `222F05`/`D946`、Transmission Temp `434E`、Boost Pressure `2BBC`、DPF Soot Mass `281A`，header 皆為 `7E0`/`7E1`）—— 表格本身查證後判定不可信

先確認引用連結是否真實存在：`uholeschak/ediabaslib`、`radelbro/BimmerDis`、`OBDb/BMW-4-Series`、`openxc/uds-c`、`kaiwen-z/bmw-enet-tool-public-wenz77-on-bimmerforums`、`secdev/scapy`（`contrib/automotive/bmw/definitions.py`）皆**真實存在**——這次連結沒有造假。

但表格裡的六組 DID 是否真的對得上任何一組真車擷取資料，實際下載 `OBDb` 組織下已有真實訊號資料的 8 個 BMW repo（通用 `OBDb/BMW` 26KB、`BMW-3-Series`、`BMW-5-Series`、`BMW-X3`、`BMW-X5`、`BMW-i3`，共約 120KB 已擷取的真實 DID 清單；另外 `BMW-E91`/`BMW-330e`/`BMW-M3`/`BMW-1-Series` 目前是空的，還沒人填資料）逐一搜尋 `F105`/`2F05`/`D946`/`434E`/`2BBC`/`281A` 這六組 DID，**一個都沒找到**。真實資料裡的 DID 集中在 `D1xx`/`DAxx`/`DDxx`/`40xx`/`43xx`/`45xx`/`4Cxx`/`57xx`/`58xx`/`5Axx`/`63xx` 這些完全不同的號碼區間，header 也不是簡單的 `7E0`/`7E1`，而是 `6F1`（tester 位址）搭配 `rax`/`eax`（回應位址／功能定址位元組）的 functional addressing，比表格描述的定址方式複雜。判定：這份表格是論壇（bimmerpost）拼湊/過時/或直接錯誤的資訊，**不採用**。

**後續使用者追問「這幾個數值真的沒有用？」，做了更進一步的查證**：直接下載 `secdev/scapy` 的 `scapy/contrib/automotive/bmw/definitions.py`（真實的 BMW UDS 逆向工程程式碼，非文件轉述）逐字搜尋六組 DID——`F105` **真的存在**，但綁定的是 `SVK`（`SVK_Backup_02`）這個資料結構，內容是 ECU 硬體/軟體/校正碼版本與燒錄狀態記錄（`HWEL`/`SWFL`/`CAFD`/`BTLD` 等欄位、燒錄相容性檢查結果），**跟水溫毫無關係**——證實表格把這個 DID 的用途完全寫錯，不是「查無資料」而是「查到但對應錯誤」。另外五組（`2F05`/`D946`/`434E`/`2BBC`/`281A`）在 `uholeschak/ediabaslib`（另一個真實的 BMW/VAG 診斷函式庫，程式碼量遠大於 scapy）裡雖然 GitHub code search 有命中，但逐一打開確認後全部是**巧合的子字串**（出現在 `ELM327V15.hex` 韌體二進位檔、`VagAdaptionActivity.cs`——VAG 是福斯集團不是 BMW、`main.lfm` UI 版面檔裡，都不是 DID 定義）。結論不變：**這份表格不可信，不採用**，但現在有更具體的反例可以佐證，不只是「找不到」。

架構性說明本身沒問題（Mode 22 + 4 位元 DID + 切換 ECU header 的概念、`ATSP6` 走 ISO 15765-4 CAN 500kbit、UDS 正回應前綴 `62`）是正確的通用知識，可以保留參考；附的 Kotlin 範例是示意用的傳統 blocking classic Bluetooth socket + `Thread.sleep`，跟本專案 BLE GATT + coroutine + `ObdCommandQueue` 的架構不同，不能照抄。

`OBDb` 組織本身（跟現有 Ford/Honda profile 同一個資料源）底下有近 30 個 BMW 車型 repo，是目前看過最像樣的 BMW 資料候選，但要真正拿來用還有兩個前提沒解決：(1) 定址方式是 `hdr`/`rax`/`eax` functional addressing，目前 app 的 `ecuHeader`/`ecuReceiveFilter`（`ATSH`/`ATCRA`）只支援單純的實體定址，需要先擴充；(2) 多數車型 repo 目前是空的，要用哪一個得先對到使用者實際車型且該 repo 已有資料。暫不建立 profile，待有具體車型需求時再評估是否值得擴充定址架構。

### 2026-09-07（第七輪：使用者上傳 `BMW 328d Full.csv.txt` + 官方 `SAE J1979-DA (2011-10)` PDF）—— 不是 BMW 專屬資料，但驗證出 14 個真正可用的標準延伸 PID

使用者上傳的 CSV 檔名雖然叫 BMW 328d，但逐一比對隨附的 **SAE J1979-DA 官方標準全文 PDF**（用 `pdftotext` 解出文字，非網路二手轉述）後，發現 CSV 裡的每一個 `ModeAndPID`（`$46`/`$0F`/`$77`/`$24`/`$10`/`$33`/`$70`/`$73`/`$7A`/`$6D`/`$5E`/`$6B`/`$78`/`$83`）**全部是標準 Mode 01 延伸 PID**（柴油/排放相關，J1979 後期版本才加入），不是 BMW 私有 Mode 22 UDS 資料——CSV 裡每一列的 Header 欄位也都寫 `Auto`，證實不需要切換 ECU header。這代表這份資料**跟上一輪查證的 BMW Mode 22 問題無關**，不能拿來當作「做 BMW 專屬 profile」的理由，但可以直接擴充 `universal-obd2.json`（因為是標準 PID，任何相容車輛都可能支援，不限 BMW）。

逐一對照官方規格書重新推導公式（不是照抄 CSV，CSV 本身的公式有錯）：
- `$24`（Lambda）CSV 寫 `*000031`（小數點被吃掉），官方係數其實是 `0.0000305`——CSV 格式壞掉但數值方向對。
- `$83`（NOx）CSV 寫 `(((B*256)+C)*0.032044)-100` 這種帶偏移量的公式，但官方規格明確寫 NOx 濃度是**純 1:1 ppm，無縮放無偏移**——CSV 這個公式是錯的，採用官方版本 `(B*256)+C`。
- `$7A`（DPF 壓力）的「差壓」（B,C）在規格書裡是 **signed 16-bit**，超出目前公式引擎能力，跳過；但「進氣壓力」（D,E）、「出氣壓力」（F,G）是無號值，可以用。
- `$6B`（EGR 溫度）規格書顯示縮放比例（1°C 或 4°C per bit）依另一個旗標位元而定，是條件式邏輯，公式引擎不支援分支判斷，跳過。

最後新增到 `universal-obd2.json` 的 14 個欄位（全部單位改回公制，不用 CSV 的 psi/°F 換算）：`chargeAirCoolerTempC`、`lambdaBank1Sensor1`、`boostPressureCommandedKPA`/`boostPressureActualKPA`、`exhaustPressureBank1KPA`、`dpfInletPressureKPA`/`dpfOutletPressureKPA`、`fuelRailPressureCommandedKPA`/`fuelRailPressureActualKPA`、`engineFuelRateLPH`、`exhaustGasTempBank1Sensor1C`/`Sensor2C`/`Sensor3C`、`noxBank1Sensor1PPM`。其中 `$78`/`$83` 需要用到 E/F/G 位元組，順便把 `PidFormula` 的變數範圍從 `A-D` 放寬到 `A-Z`（純字母對應位元組索引，沒有額外邏輯要改）。

**教訓**：附件裡的 PID 表格即使是照著正確的官方標準寫的，也不能直接照抄公式——這份 CSV 至少有兩處數值錯誤（小數點掉字、NOx 公式整個錯），只有拿到原始規格書逐條核對才敢用。「來源看起來很正式」跟「內容真的正確」是兩回事。

### 2026-09-07（第八輪：使用者貼上 BMW ECU 定址表 + Service 0x19 六位數 DTC 對照表）—— 結果混合，DTC 代碼部分可信，定址表未驗證

這次貼的是兩塊：(1) 一份「模組代號 → `ATSH<header>`」對照表（DME=`7E0`、EGS=`7E1`、DSC=`7E2`、CAS=`744`、KOMBI=`760` 等），(2) UDS Service `0x19`（ReadDTCInformation）的封包格式說明 + 12 筆 BMW 六位數原廠故障碼對照表。

**Service 0x19 封包格式**：跟前一則回覆裡從 ISO 14229 標準推導的說明一致（`59 02 <mask> <3 bytes DTC><1 byte status>` 逐筆重複），這部分是公開標準，沒有查證問題。

**DTC 代碼表**：用 WebSearch 逐筆查證（找獨立、互不相關的真實車主論壇貼文，不是同一來源複製）：
- ✅ **確認可信**：`E12C01`/`E12C03`（油箱浮筒感知器左/右）——BimmerPost、JustAnswer、YouTube 影片，多位不同車主（535i、X3、750i、335i）回報一致；`D904`（K-CAN 線路故障）多個獨立論壇串一致；`140010`（多缸失火偵測）BimmerPost、ecutesting.com 文字描述幾乎一致；`120308`（增壓合理性過低，注意跟使用者這輪貼的 `120301` 只差最後一碼，`120308` 才是查到佐證的那組）。
- ⚠️ **查無獨立佐證**（不代表假，只是找不到）：`CF18A1`、`130104`、`118001`、`D35A53`、`20A105`、`120301`。
- ❌ **查到衝突資訊**：`804033`，使用者表格寫「Terminal 15N 異常斷電」，但查到的獨立來源顯示這組代碼實際是「ZSG 喚醒輸入阻擋保護」，兩者不是同一件事，不採用。

**ECU 定址表**：拿本專案第七輪已下載的 OBDb 真實資料（涵蓋 BMW 通用/3-Series/4-Series/5-Series/X3/X5/i3，都是 2018 年後新世代車）交叉比對，**全部使用 `6F1`（tester 位址）+ `rax`（回應位址，如 `607`/`60D`/`612`/`618`/`629`/`640`/`660`）的 functional addressing**，跟使用者這份表格的 `ATSH740`/`ATSH744`/`ATSH7E2` 這種單一實體位址完全對不上。研判使用者這份表格可能對應**較舊世代**（E-series 或初期 F-series，換用新閘道器架構前），但目前沒有那個世代的真實資料可以逐條核對，**狀態是「未驗證」，不是「已驗證正確」也不是「已驗證錯誤」**——問過使用者實際車型/底盤代號以便判斷該用哪種定址，使用者暫時沒有偏好/不確定，故此表格暫不採用、也不否定，留待確認車型或有其他獨立來源時再查。

**結論**：DTC 代碼表裡確認可信的 5 筆（`E12C01`、`E12C03`、`D904`、`140010`、`120308`）可以考慮以 `verified: "forum-partial"` 等級收錄成 BMW 專屬六位數 DTC 查詢表（純顯示層查表，不需要先解決定址問題就能做）；但要能透過 BLE 實際「讀到」這些代碼（尤其是非引擎模組如 KOMBI/CAS 回報的代碼），仍卡在 ECU 定址表未驗證這一關。Service 0x19 本身的標準封包解析（不含 BMW 6 位數查表）可以獨立先做，因為是公開標準、不依賴任何未驗證資料。

### 2026-09-07（第九輪：使用者提供 BMW 原廠維修手冊掃描頁（Bentley Publishers 授權版），改抽取 Wal33D 資料庫的 BMW 子集，收錄成 `bmw.json`）

使用者這次貼的是 **Bentley Publishers**（BMW 原廠維修手冊北美授權出版商，不是網路論壇）掃描頁，內容是標準格式「P-code + BMW 內部代碼 + 英文定義」的 DTC 對照表，用詞（`EWS` 防盜、`DISA` 差動進氣、`VANOS`、`DMTL`）都是真實 BMW 術語。**關鍵是這批碼是標準 P-code 格式**，跟現有 Mode 03/07/0A 讀取機制完全相容，不需要 Mode 22、不需要定址表，是目前所有 BMW 查證裡唯一不卡在架構問題上的資料。

因為手冊是截圖而非原始檔，逐字視覺辨識密集 hex 表格風險高（截圖裡甚至有原始掃描本身的 OCR 誤讀，如 `P080I` 應為 `P0801`），改走更安全的路：發現 `shared/dtc-codes/README.md` 早就記錄「原始 `Wal33D/dtc-database` 涵蓋 BMW，只是之前沒有 BMW PID profile 所以沒抽取」——直接從這個已經驗證過的來源（跟 Ford/Mazda/Honda 同一個資料庫）抽出 BMW 子集（`data/source-data/bmw_codes.txt`，250 筆，全部是 P1xxx），比手動轉錄截圖可靠。抽查手冊截圖裡的 `P1123`、`P1511`、`P1512`、`P1513`、`P1090`、`P1620` 對照 Wal33D 的 BMW 子集，**全部一致**，兩個獨立來源互相印證。

另外用手冊裡的「OBD II Standard Fault Codes」通用碼章節（P0100-P0804）抽查現有 `generic.json`，20 筆全部吻合，確認手冊本身可信、也再次確認截圖辨識沒有引入錯誤。

**限制**：Wal33D 的 BMW 資料只有 P1xxx（250 筆），手冊裡的 P2xxx（DMTL 蒸發系統）、P3xxx（高壓噴油嘴，柴油引擎專屬）完全沒有涵蓋——這些只存在使用者的截圖裡，還沒有第二來源可以交叉驗證，暫不轉錄收錄。

**已完成**：`shared/dtc-codes/bmw.json`（250 筆，`verified` 等級同 generic/ford/mazda/honda，因為都來自同一個已驗證資料庫）、同步 Android assets、`DtcDescriptions.load()` 預設 brands 清單加入 `"bmw"`、修正 `DashboardScreen.kt` 故障碼詳情對話框原本只看 `selectedBrand`（需要有 PID profile 才會被設定，BMW 沒有 profile 所以永遠不會生效）的問題，改成優先用 `selectedBrand`、沒有時退回 `detectedBrand`（單純 VIN 辨識，不需要 PID profile）。

### 2026-09-07（第十輪：BMW P3xxx 手動轉錄 + 擴充其他 5 個廠牌的 DTC 說明）

使用者確認「可以承擔辨識風險」，請求把手冊裡的 P2xxx/P3xxx 也加入。查證後 P2xxx 全部跟 `generic.json` 重複（不是私有碼，BMW 只是用自己的術語描述同一組通用碼），跳過；P3xxx（SAE 保留的廠牌自訂區間，跟 P1xxx 一樣）視覺辨識手冊掃描頁轉錄 67 筆，全部確認不在 `generic.json` 裡，加入 `bmw.json`（現在共 317 筆）。這批 P3xxx **沒有第二來源交叉驗證**，純靠視覺辨識，細節與跳過的疑似錯誤列見 `shared/dtc-codes/README.md`。

同時確認「這批 DTC 資料是 Mode 03/07/0A（P-code）的範圍，跟 Mode 22／第六～八輪查證的定址問題無關，不需要擴充定址架構（項目 C）」——回答使用者的技術提問。

既然 Wal33D 資料庫的可信度已經多次驗證（Ford/Mazda/Honda/BMW P1xxx 都抽查一致），使用者要求擴充其他廠牌。從同一個資料庫再抽取 Toyota（45 筆）、Mercedes-Benz（32 筆）、Volkswagen（528 筆）、Kia（76 筆）、Mitsubishi（34 筆）——這 5 家目前都**沒有**私有 PID profile，只提供故障碼說明（跟 BMW 一樣，靠 `detectedBrand` 而非 `selectedBrand` 生效）。`VehicleBrandDetector` 支援的 13 個廠牌裡，Audi、Hyundai、Peugeot、Citroën 這個資料庫沒有涵蓋，維持原狀。

`DtcDescriptions.SUPPORTED_BRANDS` 新增為公開常數（`load()` 預設清單的單一來源），並在 App 右上角「⋮」選單加了「支援廠牌」項目，列出目前有即時參數（PID）跟有故障碼說明的廠牌清單，供使用者查閱。

### 2026-09-07（第十一輪：使用者詢問「通用型 OBD PID 是否都已建入」，回頭比對官方 SAE J1979-DA 全文）

用第七輪已下載的 SAE J1979-DA（2011）標準全文比對，Mode 01 總共定義 123 個 PID，`universal-obd2.json` 當時只有 30 個——差距主要是重型柴油/混合動力/電動車/行車紀錄器等冷門區間，不需要全部補齊。挑出常見、任何乘用車都可能支援、且是單一數值（非狀態列舉）的 9 個補上：`$43` 絕對負載、`$44` 指令當量比、`$45` 相對節氣門位置、`$52` 乙醇燃料百分比、`$59` 燃油軌絕對壓力、`$5A` 相對加速踏板位置、`$61`/`$62`/`$63` 引擎扭矩（駕駛需求/實際/參考值），公式都對照規格書重新推導。

`$03`（燃油系統狀態）、`$51`（燃料種類）是狀態列舉值（如「開環/閉環」「汽油/柴油/電動」），`$64`（引擎扭矩曲線圖）是含 5 個轉速/扭矩對應點的完整曲線圖——三者都不是公式引擎能處理的單一數值，先跳過，等未來需要列舉值/多點資料顯示時再評估。

### 2026-09-07（第十二輪：使用者提問「Mode 01 PID 是否等同 Mode 22 DID，是否該避免重複查詢」——排查廠牌 profile 有無跟通用 PID 重複）

使用者的觀察是對的：Mode 01 PID 跟 UDS Mode 22 DID 功能上都是「用識別碼讀取當前數值」，但兩者是各自獨立的定址空間，不能互通；不過如果某數值已經有標準 Mode 01 PID，就不該再為同一件事另外查該廠牌的 Mode 22 DID（多一次請求、可能還要多切一次 ECU header）。

逐一比對現有廠牌 profile 的欄位跟 `universal-obd2.json`：`ford.json`（里程、胎壓警示、四輪胎壓）、`mazda.json`（四輪胎壓）都沒有重複，這些本來就是標準 PID 沒有涵蓋的資料。`citroen.json` 抓到 4 筆真的重複：`psaRpm`（`22D400`）重複 `rpm`（`010C`）、`outsideTempC`（`22D912`）重複 `ambientAirTempC`（`0146`）、`engineOilTempPSA_C`（`22DB83`）重複 `engineOilTempC`（`015C`）、`fuelLevelPercentPSA`（`22D8C4`）重複 `fuelLevelPercent`（`012F`）——這 4 筆已經從 `citroen.json` 移除。`mapPressureBar`（`22D4D9`）雖然跟 `intakeManifoldPressureKPA`（`010B`）概念相近，但渦輪引擎的 MAP 感測點位置可能跟標準感測器不同，證據不足以判定重複，保留。

### 2026-09-07（第十三輪：使用者提出 [openvehicles/Open-Vehicle-Monitoring-System-3](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3) 及一份德文論壇 ELM327 AT 指令/DID 表，詢問能否取得雪鐵龍 Berlingo 資料；並追問「油版是否需要跳線，能否用軟體層解決」）

用 `gh api`/`curl` 查證 OVMS v3 repo 為真實專案，`vehicle_fiatedoblo` 元件文件記載其相容車款含「Peugeot e-Rifter, Opel Combo-e, Citroën **Berlingo** 及 Toyota ProAce City」，並逐一比對貼上的德文論壇表格跟 `vehicle_fiatedoblo.cpp` 原始碼——header/DID/公式幾乎完全對得上（`0x6a2`/`0x682` 讀環境溫度/電池溫度 DID `0xd434`/`0xd8ef`；`0x6b4`/`0x694` 讀電池電壓/電流/最小最大單體電壓/kWh/SOH DID `0xd815`/`0xd816`/`0xd86f`/`0xd870`/`0xd865`/`0xd860`，公式：電壓=value2/16、電流=(3 bytes)/64−1200、SOH=((3 bytes)−65536)/16、單體電壓=value2/1000、kWh=value2/64）。

**但這整組資料是純電動車電池管理系統專屬的**（SOC/SOH/kWh/電池電壓電流），對燃油車完全沒有對應項目，**不會**加入 `citroen.json`。且 OVMS 官方文件自稱鎖定的是 Stellantis **EMP2 平台（2022–2024 年 e-Doblo/ë-Berlingo/e-Rifter 等純電版）**，跟使用者實測的燃油版 Berlingo 很可能是不同世代的車架/電子架構——先前實測（Mode 09 讀不到 VIN、大量 NO DATA/負回應）比較像是較舊、非 CAN（K-Line/ISO9141 或 KWP2000）的世代，OVMS 這組電動車發現**不能直接套用**在這台燃油測試車上。

使用者接著問：OVMS 官方文件自稱「Works with a Generic OBD2 OVMS Cable」（不需跳線），但另一則法國論壇資料聲稱 PSA 的專屬診斷 CAN bus 落在非標準的 OBD-II 第 3/8 腳位、需要硬體改線——如果油電版真的需要跳線，能否用軟體層解決？

**結論：如果真的是接腳沒有實際接到轉接器內部收發器的硬體限制，軟體層無法解決**——這是電路是否導通的物理層問題，不是通訊協定或指令能繞過的（收發器沒接到那兩根針腳，送再多 AT 指令都送不出訊號、也收不到回應）。但兩個資訊來源本身可能在講不同的匯流排：OVMS「免跳線」講的是 e-Berlingo（EMP2 平台）**接引擎/電池管理系統的標準 CAN 匯流排**（pin 6/14），而法國論壇講的第 3/8 腳位「PSA 專屬診斷匯流排」通常是用來連 BSI（車身控制模組）、舒適系統等**非引擎**模組的內部多工匯流排，兩者性質不同、不衝突。也就是說：既然這台燃油 Berlingo 已經能透過標準腳位讀到電瓶電壓、水溫（證明標準 CAN/K-Line 匯流排本身是通的），VIN 讀不到、廠牌 PID 覆蓋率低，比較可能是「這台車的 ECU 在標準匯流排上本來就不支援 Mode 09／某些 Mode 22 DID」的協定限制，而不是接錯腳位——這點應該在確認實際車架年份/世代前，不要假設一定要跳線。

同一輪，使用者貼了一份 UDS 建議：改用 `22 F1 90`（Read Data By Identifier，DID `F190` 為標準 VIN 識別碼）取代 `09 02` 讀 VIN，並提到需要自行實作 ISO-TP 多幀重組（First Frame/Flow Control/Consecutive Frame）。`F190` 確實是 ISO 14229 標準保留的 VIN DID，這部分可信；但 ISO-TP 重組只有在**直接操作原始 CAN 匯流排**時才需要自己做——本 App 是透過 ELM327 相容的 AT 指令介面跟轉接器溝通，轉接器晶片本身已經處理好 CAN 分幀/流控，App 收到的就是已重組好的一行文字回應（如 `62 F1 90 57 30 4C ...`），**不需要、也不應該**在 App 裡再實作 ISO-TP。下一次連車測試時，可以直接用現有「手動指令」功能送 `22F190`（沿用目前偵測到能通標準 PID 時的 ECU header，不需先跳線）試看看這顆引擎 ECU 是否支援這個標準 DID；如果能讀到 VIN，可以之後串進 `DashboardViewModel` 作為 Mode 09 失敗時的備用 VIN 讀取路徑。

### 2026-09-07（第十四輪：使用者要求「develop OVMS logic」+「加入 22F190/ECU 支援測試功能到右上角」——把第十三輪的分析落實成程式碼）

**OVMS EV 資料**：下載 `vehicle_fiatedoblo.cpp` 全文（前一輪只查證了 header/DID 對得上，這輪逐行比對 `IncomingBatteryPoll`/`IncomingVCUPoll` 的實際解碼運算式，重新推導成本專案的 `formula`/`bitField` 語法，不是憑記憶轉抄）：
- 電池電壓 `22D815`：`((A*256)+B)/16`
- 電池電流 `22D816`：`((B*65536)+(C*256)+D)/64-1200`（原始碼用的是 `data[1..3]`，不是 `data[0..2]`，要特別注意位元組偏移）
- 最小/最大單體電壓 `22D86F`/`22D870`：`((A*256)+B)/1000`
- 可用電量 `22D865`：`((A*256)+B)/64`
- SOH `22D860`：`((A*65536)+(B*256)+C-65536)/16`
- 環境溫度 `22D434`、電池溫度 `22D8EF`：原始碼是 `(int8_t)data[0]`，即有號位元組，四則運算公式引擎無法表達「有號」，改用 schema v3 的 `bitField`（`bitIndex:0, bitLength:8, signed:true`）
- DC-DC 轉換器溫度 `22D8CE`、車載充電器溫度 `22D8E1`：`(int8_t)data[1]`，同樣用 `bitField`（`bitIndex:8, bitLength:8, signed:true`）
- 原始碼裡另外還有 `0xD8CD`（驅動馬達溫度，作者自己註記 `// just a guess, TODO: check it!`）、`0xD8F9`（冷卻液溫度，只出現在輪詢清單、沒有對應的 switch-case 解碼邏輯）、`0xD410`（馬達扭矩，作者自己標記 `// guessed:`）——三者都因為連原作者都不確定/沒有解碼邏輯，未收錄，避免比論壇資料還不可信的猜測進到 `shared/`。

新增 `shared/vehicle-profiles/citroen-ev.json`（`brand: "Citroen"`，`verified: "community"`），在 `VLinkerObdApplication.kt` 跟既有的 `citroen.json`（燃油 DS4 profile）合併成同一個 `psaProfile`（`pids` 陣列相加），不另外做 EV/ICE 車型切換 UI——理由是本專案既有的失敗退避機制（`GIVE_UP_THRESHOLD`/`COOLDOWN_ROUNDS`）本來就會讓車輛答不出來的 PID 自動降頻，燃油車遇到這些 EV-only DID 會全部 NO DATA 然後自動退避，跟其他查不到的 PID 一視同仁，不需要多一層車型選擇的複雜度。

**ECU 支援測試工具**：在右上角選單新增「ECU 支援測試」，對應 `DashboardViewModel.testEcuSupport()` — 依序送出 `0100`（確認標準匯流排本身有無回應）、`0902`（Mode 09 VIN）、`22F190`（UDS 讀 VIN），並將結果分類成「有回應／NO DATA／拒絕(7F)／逾時／無法解析」五種狀態顯示給使用者；若 `22F190` 被拒絕或無回應，會自動追加送 `1003`（切換至延伸診斷 Session）後重試一次，最後用 `1001` 還原預設 Session。這是純讀取型的診斷探測（沒有寫入/清除任何 ECU 狀態的指令），用來幫使用者快速判斷「這台車到底是協定/服務不支援，還是接線問題」，呼應第十三輪的結論。已補上 `DashboardViewModelTest` 的兩個情境（直接成功、被拒絕後靠 1003 重試成功）並跑過 `testDebugUnitTest`/`assembleDebug` 全部通過。

### 2026-09-08（第十五輪：使用者貼上 [ludwig-v/arduino-psa-diag](https://github.com/ludwig-v/arduino-psa-diag) 與 [prototux/PSA-RE](https://github.com/prototux/PSA-RE)——證實 pin 3/8 硬體限制確有其事）

`ludwig-v/arduino-psa-diag`：真實存在、活躍維護（222 星、近期仍有更新），是拿 Arduino 直接送 UDS/KWP 診斷封包給 PSA 車的工具，README 有明確的 Dump Mode 接線圖：

| 平台 | 診斷 CAN 接腳 |
| - | - |
| **AEE2004 / AEE2010** | **Pin 3（CAN-H）／Pin 8（CAN-L）** |
| NEA2020（2020 年後新平台，改用 DoIP） | Pin 6／Pin 14 |

這**直接證實**第十三輪懸而未決的法國論壇說法：PSA 較舊平台（AEE2004/AEE2010）的診斷用 CAN bus 真的不在標準 OBD-II pin 6/14 上，而是 pin 3/8——不是二手轉述、是另一個獨立、活躍、有實際程式碼佐證的來源。

`prototux/PSA-RE`（第四輪已查證為真實但作者已停止維護，只有被動監聽的車身網路資料，未涵蓋診斷層），這次額外抽查 `architectures.yml`：確認 `AEE2004`/`AEE2010` 平台字面定義為「Full-CAN」架構，明確分成 `HS`（500kbps，主要 ECU 掛在這條，`protocols` 欄位列出 `EOBD` 與 `UDS` 兩者並存）與 `LS`（125kbps，車身/娛樂系統用）兩條實體匯流排；`AEE2010` = 2010～2020 年左右生產的車輛。這解釋了為什麼同一顆引擎 ECU 可以「pin 6/14 能查到標準 Mode 01 PID（EOBD 子集），但 pin 3/8 才是完整 UDS 存取」——兩種診斷指令集可能同時存在於同一條實體 HS 匯流排上，只是 PSA 刻意把兩種存取管道接到 OBD-II 接頭上不同的針腳，讓一般通用掃描工具（含 vLinker）只能碰到閹割過的 EOBD 子集。

**結論更新**：使用者的燃油版 Berlingo（先前已由 VIN/Mode 09 讀取失敗等症狀推斷是較舊世代）很可能就是 AEE2004 或 AEE2010 平台，這代表**深度私有 PID 資料確實需要接到 pin 3/8 才拿得到，vLinker 這類通用轉接器的收發器沒有接到那兩根針腳，是真正的硬體限制，軟體層無法繞過**——呼應並強化第十三輪「若真是接腳問題，軟體無法解決」的結論，不再只是推測。務實可行的路只剩：換一條有額外接到 pin 3/8 的診斷線材/轉接器（如 arduino-psa-diag 展示的 OBD2 延長線分接法），這是硬體採購問題，超出本 App 能處理的範圍；而 `22F190`／`0100` 等標準指令因為走的是 pin 6/14 那條合法規 EOBD 匯流排，跟這個限制無關，仍值得實測。

### 2026-09-08（第十六輪：使用者用「ECU 支援測試」實車連線截圖回報，帶出兩個真的程式碼 bug——不是資料查證，是工程排查）

第一張截圖顯示大量 `speedKPH：條件不符`／`rpm：條件不符`／`controlModuleVoltage：條件不符`／`coolantTempC：條件不符` 洪水式出現，最後 `已中斷連線`。追查 `DashboardViewModel.restartBrandPolling()` 發現：查完一個有 `ecuHeader` 的廠牌 PID 後，程式碼原本用 `ATSH00` 想切回預設 header——但這是格式錯誤的指令（ELM327 header 要 3 位十六進位如 `7DF`，`ATSH00` 只有 2 位），轉接器很可能直接忽略它，導致 header 卡在上一顆廠牌 PID 的值（例如 `citroen.json` 渦輪壓力用的 `6A8`），後續每一輪標準 Mode 01 輪詢都送到這個錯誤的 header，對應的 ECU 自然回覆 `7F ... 22`（條件不符）——完全符合截圖的洪水症狀，且不會自己恢復。已改成正確的 `ATSH7DF`（標準 11-bit 功能性廣播位址），並補上 `DashboardViewModelTest.restoresStandardFunctionalHeaderAfterBrandPidWithEcuHeader` 重現整個情境（VIN 成功辨識出 Citroën → 觸發廠牌輪詢 → 查完 PID 後驗證下一個指令是 `ATSH7DF` 而非 `ATSH00`）。這個路徑先前完全沒有測試覆蓋，也是這次現場測試中第一次真的被觸發到（先前每一輪 VIN 都直接 NO DATA，從未真正進入過廠牌輪詢的 header 切換邏輯）。

第三、四張截圖是「ECU 支援測試」的結果：`0902`（Mode 09 VIN）這次不是乾脆的 NO DATA，而是收到一段看起來像多幀重組回應的原始文字（推測格式類似 `014` 開頭的長度欄位，接著 `0:`/`1:`/`2:` 標號的分幀行），但 App 自己的分類器把它判定成「無法解析的回應」。使用者無法直接複製精確文字（只有照片），為避免憑模糊照片猜確切位元組內容、寫出可能錯誤的修法，先確認了通用的格式規律：ELM/STN 相容轉接器在多幀回應（如 20 bytes 的 VIN）確實會出現「獨立一行的十六進位長度欄位」+「每行前綴 `<n>:` 標示幀序」這種展示方式，屬於已知格式規律，不需要知道 VIN 實際內容就能修對。在 `ObdResponseParser.normalize()` 補上：多行回應的第一行若是單獨、不含空白的 1–4 位十六進位字元（無法跟後面的資料行搞混，因為真正的資料行一定有多個以空白分隔的位元組），視為長度欄位捨棄；每一行開頭若有 `數字:` 前綴，視為分幀序號一併去除。補上 `ObdResponseParserTest.parsesVinFromLengthPrefixedIndexedMultiFrameResponse`，用同樣格式規律、但内容是自建的一致範例（VIN `VF7ABCDEFGH123456`，20 bytes 依 7-byte 一幀切成 3 幀）驗證修好，不依賴精確重現照片裡看不清楚的原始位元組。

兩個修正都跑過 `testDebugUnitTest`／`assembleDebug` 全部通過，`22F190` 兩次仍是 NO DATA，跟第十五輪的 pin 3/8／EOBD-vs-UDS 結論不衝突（`22F190` 走的是 pin 6/14 那條合法規匯流排，這個限制跟 header 切換 bug、VIN 多幀解析 bug 都無關）。

### 2026-09-08（第十七輪：修好解析器後實測，VIN 真的讀到了但廠牌辨識不出來——`VehicleBrandDetector` 缺一個 WMI）

部署上一輪的兩個修正後重測，log 顯示 `車款辨識：VIN=VR7ECYHZRNJ613202，無法辨識廠牌`——第十六輪的多幀解析修正確實生效（VIN 完整讀出來了），但 `VehicleBrandDetector` 的 WMI 對照表只收錄了 Citroën 的 `VF7`／`VS7`，沒有這台車 VIN 開頭的 `VR7`。

用兩個獨立、真實存在的開源 VIN 解碼專案交叉驗證（不只查一個來源）：
- [idlesign/vininfo](https://github.com/idlesign/vininfo)（Python VIN 解碼套件，`src/vininfo/dicts/wmi.py`）：`'VR7': 'Citroën'`
- [way-platform/vin-go](https://github.com/way-platform/vin-go)（Go VIN 解碼專案，有專門處理 Stellantis 集團 VIN 的模組）：`internal/oem/stellantisvin/infer.go` 裡明確寫 `case "VF7", "VR7": // Citroën (France / Spain)`——特別註記 `VR7` 是**西班牙廠**（PSA Vigo 廠）生產的 Citroën，`VF7` 才是法國廠。

兩個獨立來源一致，可信度足夠。已在 `VehicleBrandDetector.kt` 的 Citroën WMI 清單加入 `VR7`，補上 `VehicleBrandDetectorTest.detectsCitroenFromSpainPlantWmi`（直接用這台車實測讀到的 VIN 當測試案例）。`testDebugUnitTest`／`assembleDebug` 全部通過。這台車生產地是西班牙 Vigo 廠，也剛好呼應第十五輪查到的「AEE2010 平台、PSA 集團共用引擎/車身電腦世代」的推論——不同廠區生產同一個平台的車，VIN 開頭碼不同是正常的。

### 2026-09-08（第十八輪：使用者要求「若不支援就不要去查詢了」——把逾時 PID 的退避機制從「偶爾重試」改成「永久放棄」）

`timingAdvanceDegrees` 在現場測試中持續 `NO DATA`（這台車本來就不支援），但 `restartStandardPolling()`／`restartBrandPolling()` 原本的退避機制只是「連續失敗 5 次後，改成每 20 輪才重試一次」（`COOLDOWN_ROUNDS`），不是永久停止，所以還是會在 log 裡偶爾看到重複的失敗訊息。使用者要求：確認不支援後就完全不要再查。

改法：拿掉 `round % COOLDOWN_ROUNDS != 0` 這個「偶爾重試」的例外，`failureStreak[pid.field] >= GIVE_UP_THRESHOLD` 之後永久跳過該 PID（直到下次重新連線，因為 `failureStreak` 是連線時建立的區域變數）。連帶移除已無用的 `COOLDOWN_ROUNDS` 常數與 `round` 計數器。

**這裡踩到一個差點引入的真 bug**：把「略過」跟「跳過本次 delay」寫在一起的話，如果一個輪詢清單裡**所有** PID 都放棄了（例如這台車的 `citroen.json`+`citroen-ev.json` 私有 PID，因為 pin 3/8 硬體限制幾乎全部查不到——見第十五輪），`for` 迴圈會整輪都直接 `continue`、完全不會執行到任何 `delay(...)`，導致這個 coroutine 變成沒有任何暫停點的無限忙迴圈（busy-loop），在真實裝置上會讓一個 CPU 核心被卡滿、電量狂掉。修法是讓「放棄」分支也照樣 `delay(BRAND_POLL_INTERVAL_MS)` 再 `continue`，確保不管有幾個 PID 放棄，每一輪一定會經過至少一次暫停點。

補上 `DashboardViewModelTest.stopsPollingStandardExtraPidPermanentlyAfterRepeatedFailures`：先驅動到某個標準延伸 PID 連續失敗 5 次放棄，再快轉 200 秒虛擬時間，斷言完全沒有再送出該 PID 的指令——如果 busy-loop 的修法沒做對，這個快轉呼叫會直接卡死不返回，而不是斷言失敗，等於順便驗證了不會卡死。`testDebugUnitTest`／`assembleDebug` 全部通過。

### 2026-09-08（第十九輪：使用者要求「收集市面上所有的 WMI 並加到資料庫中，以避免遺漏」——把手刻的 ~90 筆 WMI 換成完整資料庫）

第十七輪修 `VR7` 是頭痛醫頭：`VehicleBrandDetector.kt` 原本是寫死在程式碼裡的一個小表，只收錄本專案目前有 PID profile 的十幾個廠牌、每個廠牌只列幾組常見 WMI，本質上就是「遇到一個補一個」，不可能不再漏。

改用真實存在的完整資料庫，而不是手動一筆一筆找：下載 [idlesign/vininfo](https://github.com/idlesign/vininfo)（MIT 授權、活躍維護的 Python VIN 解碼套件）的 `src/vininfo/dicts/wmi.py`，約 700 筆，涵蓋北美/歐洲/南美/中國/其他亞洲市場的車廠，含轎車、卡車、巴士、機車。原始碼裡部分項目不是純字串，而是 `Nissan()`、`Renault('Infiniti')` 這類物件——沒有用猜的，直接讀 vininfo 自己的 `common.py`（`Assembler.__init__`: `manufacturer = manufacturer or self.title`，`title` 是類別名稱）確認這些物件轉成字串後的實際值，再用一支轉換腳本（`convert_wmi.py`，僅本機執行、未收錄進 repo）把整份表轉成 `shared/wmi-database/wmi-to-brand.json`。

轉換過程中把拼法/區域合資品牌的變體正規化成本專案 `shared/vehicle-profiles/*.json` 裡 `brand` 欄位已經在用的字串（例如 `"Mercedes Benz"`→`"Mercedes-Benz"`、`"Citroën"`→`"Citroen"`、`"FAW-Volkswagen"`→`"Volkswagen"`——後者是真的掛 VW 廠徽在賣的中國合資廠，不是不同廠牌），這樣比對到既有廠牌時能繼續自動選中對應的 PID profile；但像 `"DaimlerChrysler AG/Daimler AG"` 這種歷史上可能是 Mercedes-Benz 或 Chrysler 任一款車共用的 WMI，刻意不正規化、保留原始標籤，避免用猜的歸類到錯的廠牌。

**誠實揭露涵蓋範圍**：這是「相對完整」，不是「窮舉」——沒有任何單一免費公開來源涵蓋所有曾經核發過的 WMI（SAE 是美洲的官方註冊機構，其他國家各自有自己的核發單位，這份清冊本身也是別人整理自各種二手來源），~700 筆已經涵蓋消費用 OBD-II App 實務上絕大多數會遇到的車，包含這次的導火線 `VR7`，但真的很冷門或剛核發的 WMI 還是可能查不到。`detectBrand()` 回傳 `null` 的意思是「這份資料庫今天沒收錄」，不是「這不是真車」——這點寫進了 `shared/wmi-database/README.md`，往後再遇到類似 `VR7` 的個案，做法一樣：補進去、附來源、記錄在這裡。

架構上把 `VehicleBrandDetector` 從寫死的 `object` 改成吃 `Map<String, String>` 的 `class`，比照 `PidGroupRepository` 的模式從 JSON asset 載入（`VehicleBrandDetector.loadFromJson()`），另外保留一個 `VehicleBrandDetector.FALLBACK`（就是原本那張小表，含 `VR7`）當作沒載入完整資料庫時的預設值——單元測試與 `DashboardViewModel` 的預設參數都用這個，production 則在 `VLinkerObdApplication` 明確載入完整的 `wmi-database/wmi-to-brand.json`。順帶支援了來源表裡本來就有的 2 碼萬用碼（例如 `"JT"`→Toyota，代表沒有被特定 3 碼細分的車廠家族碼）：查詢時先比對完整 3 碼，找不到才退而比對前 2 碼。補上 `VehicleBrandDetectorTest` 的 JSON 載入與 2 碼 fallback 測試，`testDebugUnitTest`／`assembleDebug` 全部通過。

### 2026-09-08（第二十輪：VR7 修好後實測——`citroen.json` 的渦輪相關 PID 在這台車上確認不可用，另修一個 BLE 服務探索卡死的 bug）

VR7 修好、廠牌成功辨識成 Citroen 後，第一次連上真的開始輪詢 `citroen.json` 的私有 PID，log 顯示 `turboPressureBar`／`turboPressureSetpointBar`／`turboTempC` 三個都回 `7F 22 31`（分類成「請求超出範圍」，NRC `31`）。

**這不是 bug，是 ECU 誠實的回應**：NRC `0x31`（requestOutOfRange）是 UDS 標準定義的「服務本身合法、但這個識別碼在這台 ECU 上不存在/不支援」，跟 `NO DATA`（逾時無回應）是不同層級的失敗——能收到明確的 `7F` 拒絕，代表 header `6A8`/`688` 確實連到一顆真的會處理 Mode 22 請求的 ECU，只是這三個 DID（`22D47E`/`22D48D`/`22D47F`）在這台車的引擎 ECU 軟體版本上沒有實作。`citroen.json` 的這批 PID 原本就是從單一一台 2016 Citroën DS4（EP6FDTX 引擎）逆向出來的（見 README／JSON 內 `notes`），本來就註記「同引擎/BSI 世代的其他廠徽車型很可能適用，但未逐一實測」——現在有了這台 VR7 Berlingo 的實測反例，證實**不是所有 PSA 車輛都共用同一組渦輪感測器 DID**，符合預期的不確定性，不需要改程式碼；既有的永久放棄機制（第十八輪）已經會在連續失敗 5 次後自動停止查詢這三個欄位。

同一輪也修了一個真的 bug：使用者回報「APP 第一次啟動、自動連上曾配對過的裝置後，會卡在『探索服務中』不動，要強制關閉 APP 重開才會恢復」。查 `BleObdManager.kt` 的 `onConnectionStateChange`，發現 GATT 連線成功後呼叫 `discoverServices()`，但**完全沒有處理逾時或失敗**——Android 的 BLE 堆疊已知會偶爾在呼叫 `discoverServices()` 之後永遠不觸發 `onServicesDiscovered` 回呼（尤其容易發生在 App 啟動後第一次自動重連，之後手動重新連線反而正常，這正好對上使用者描述的症狀），導致連線狀態永遠卡在 `DISCOVERING_GATT`，沒有任何錯誤訊息、也沒有重試。修法：新增一個 5 秒逾時的看門狗（`startDiscoveryWatchdog`）——逾時就重試一次 `discoverServices()`，再逾時就直接斷線讓既有的重連邏輯接手，而不是靜默卡死。`BleObdManager` 直接操作 Android BLE 框架類別，沒有對應的純 JVM 單元測試（跟其他同類別檔案一致，只能靠 `BleObdClient` 介面的 fake 測試上層邏輯），這次修正需要實機驗證才能確認解決。

### 2026-09-08（第二十一輪：實測發現第二十輪的 BLE 看門狗修正把情況搞得更糟，改成更有耐心的重試策略；另外處理圖示超出邊界、版面留白、橫式閃退、檔位更新頻率/單位）

第二十輪的 5 秒逾時看門狗部署後實測，log 顯示 `服務探索逾時，重試一次` 接著 `服務探索重試後仍逾時，中斷連線`——結果比修之前更差：使用者回報「現在第一次都不會自動連線，5 秒斷線後也不會再自動連線」。回頭檢視：第二十輪的假設（`onServicesDiscovered` 永遠不會被呼叫）可能下錯結論——比較合理的解讀是探索服務本來就會**偶爾很慢**（尤其冷開機第一次連線），5 秒／重試 5 秒的預算太短，反而在真的還在跑的連線完成前就先把它砍斷；而且 `DashboardViewModel` 的自動連線只有「每次 App 啟動嘗試一次」的機制（`autoConnectAttempted` 旗標），被看門狗砍斷的那次已經用掉這唯一一次機會，之後就永遠不會再自動重連，除非手動重開 App。

兩處都改：
- `BleObdManager`：逾時時間拉長到 15 秒，重試次數上限 2 次；而且重試不再只是對同一個（可能已經卡死的）`gatt` 物件重呼叫 `discoverServices()`，而是關閉舊的 GATT、對同一台裝置重新走一次完整的 `connectGatt()`（新增 `beginGattConnection()` 共用邏輯），避免問題出在連線本身而不只是探索服務這個環節。真的兩次都逾時才放棄斷線。
- `DashboardViewModel`：只有「本來已經 READY/INITIALIZING、後來意外斷線」（`DISCONNECTED_AFTER_ERROR`）才會重設 `autoConnectAttempted` 並重新開始掃描——讓連線失敗後能自己找回曾連線過的裝置重試，不需要使用者手動重開 App；一般的手動斷線（`DISCONNECTED`）或掃描/權限錯誤（`ERROR`）則不會觸發，避免不必要地重掃。

其餘回報項目：
- **App 圖示超出邊框**：第一版圖示直接沿用 App 內儀表板的圓弧半徑（36），結果被這支手機啟動器的 adaptive icon 遮罩裁切露出鋸齒——實際機型遮罩比官方安全區規格裁得更緊。全部幾何縮小到半徑 30，並在儀表弧本來就空出來的底部扇形加了一個排氣閥造型的小配件（呼應使用者提供的 iOBD2／Car Scanner ELM OBD2 參考圖示的引擎意象），維持儀表指針為主要造型不變。
- **標題列與狀態列間留白過大**：`LazyColumn` 的 `contentPadding` 頂部從 12dp 降到 2dp，`TopAppBar` 高度從預設 64dp（是給雙行標題留的，但廠牌+VIN 已經合併成一行）改成 `expandedHeight = 48dp`。
- **橫式閃退＋BLE 斷線**：`MainActivity` 的 `LaunchedEffect(Unit) { requestScanOrStart() }` 會在 Activity 因旋轉被系統重建時再跑一次，對著已經連線中的裝置重新呼叫 `startScan()`，把 `BleObdManager` 的連線狀態污染成 `SCANNING`。修法是在 manifest 幫 `MainActivity` 鎖定 `android:screenOrientation="portrait"`——這個儀表板本來就是雙儀表並排的直式版面設計，鎖定直式可以整類問題一次避開，不需要另外做橫式版面。
- **檔位更新太慢、單位不該是 raw**：`citroen.json` 目前約 20 個私有 PID 共用同一個 3 秒／顆的慢速輪詢清單，`gearRaw` 排在清單裡，一輪跑完可能要等將近一分鐘才輪到它再問一次。新增 schema v4 欄位 `fastPoll`（見上方章節），把 `gearRaw` 標成 `fastPoll: true`，讓它獨立跑一個 800ms 的輪詢迴圈，不受同一份 profile 裡其他變化很慢的 PID 拖累。單位從 `raw` 改成『檔』：回頭讀原始碼 `nico1080/OBD-LCD-display-for-PSA` 的 `OBD-LCD.ino`，確認這個位元組原本就是直接當手排檔位數字顯示（`if (gear >= 1 && gear <= 6) print(gear)`），沒有 P/R/N/D 對照表，也沒有處理範圍外的值——`gearRaw` 這個公式本來就沒有算錯，只是顯示單位不對，已在 JSON 的 `notes` 裡記錄清楚這個限制。

**待查、非本輪修正範圍**：`mapTempC`（進氣溫度 MAP）這次實測回報 `-39.0 degC`，對一輛正在運轉的引擎而言明顯不合理（除非在極端低溫環境）；現有公式 `A*0.75-48` 是直接沿用 `citroen.json` 既有的、未逐一驗證的資料，這次沒有新的可信來源可以判斷是公式本身錯誤還是這台車的感測器讀值方式不同，先記錄下來，不在本輪動它。

### 2026-09-08（第二十二輪：使用者貼參考 App 截圖問「這些資料能加入嗎」——拆解哪些是真 PID、哪些是算出來的、哪些跟 OBD 無關，並全面啟用 `universal-obd2.json`）

使用者貼了 7 張別的 OBD 儀表板 App 截圖（巡航/車身狀態/HUD/油耗/怠速/競技等多頁儀表），問裡面的參數能不能加進來。逐一拆解：

1. **轉速、車速、水溫、電瓶電壓、點火正時、計算負荷值（引擎負載）**——全部是真的標準 Mode 01 PID，而且早就定義在 `universal-obd2.json` 裡；`engineLoadPercent` 只是定義了但沒有被排進 `STANDARD_EXTRA_FIELDS` 實際輪詢清單，補進去即可。
2. **瞬時油耗、平均油耗、總耗油量、行駛時間、行駛里程、加速度**——沒有一個是車子直接回報的 PID，全部是業界通用的「行車電腦」算法：瞬時油耗用進氣流量 MAF（本來就有 `mafGramsPerSec` 這個標準 PID）換算成油耗率，配上車速算 L/100km；里程/時間靠速度對時間積分；加速度是車速對時間微分。這批「衍生值」用 `AskUserQuestion` 跟使用者確認範圍（先做不含油價/花費），確認後才動手。
3. **羅盤方位**——跟 OBD 完全無關，是手機自己的磁力感測器，已經在先前規劃裡歸類到「未來導航功能」，這輪不做。

**行車電腦實作**：`DashboardViewModel` 新增 `updateTripComputer()`，在既有的快速輪詢迴圈（原本只有轉速+車速）裡加一個 MAF 查詢（`mafGramsPerSec`，不放進慢速清單，因為瞬時油耗要跟車速同一個節奏才有意義），每個 tick 算：
- 瞬時油耗＝`MAF*3600/(14.7*750)` 換算成 L/h，再除以車速 ×100 得 L/100km（車速趨近 0 時改顯示 L/h，因為 L/100km 在低速時會趨近無限大，沒有意義）——公式假設汽油理論空燃比 14.7、密度 750 g/L，如果是柴油車常數會有點不同（約 14.5、832 g/L），但量級不會差太多；這**永遠是估算值，不是原始 PID**，這點寫進了程式註解。
- 平均油耗＝累計耗油量／累計里程 ×100
- 里程／時間用車速對輪詢週期（200ms）積分
- 加速度＝車速變化量／時間

有個容易忽略的坑：如果在「暫停快速迴圈」期間（例如手動指令、ECU 支援測試可能暫停好幾秒）還繼續用最後一次讀到的車速去積分里程/油耗，會把靜止不動的那幾秒錯當成「一直維持那個車速在跑」，虛長出不存在的里程——已加上判斷，暫停期間只累計「行駛時間」（這個本來就該照跑），不累計里程/油耗/加速度。補上 `estimatesInstantFuelConsumptionFromMafAndSpeed`／`showsInstantFuelConsumptionInLitersPerHourWhileStationary` 兩個測試鎖住換算公式。

**全面啟用 `universal-obd2.json`**：使用者接著要求「有定義在資料庫裡、OBD 又有支援的都應該顯示」——`STANDARD_EXTRA_FIELDS` 原本只有 4 個欄位，`universal-obd2.json` 其實定義了 40 個（扣掉車速/轉速/MAF）。既然這個 App 既有的「連續失敗 5 次永久放棄」機制本來就會讓車輛答不出來的 PID 自動停止查詢，多開 36 個欄位不會有額外風險，只是多花一點頻寬——全部加進輪詢清單。其中 `throttlePercent`／`intakeManifoldPressureKPA`／`relativeThrottlePercent`／`relativeAcceleratorPedalPercent`／`driverDemandTorquePercent`／`actualEngineTorquePercent` 這幾個會隨駕駛動作即時變動，標成 `fastPoll: true`（沿用第二十一輪剛做的機制，這次也把 `restartStandardPolling()` 一起接上快慢分流，跟 `restartBrandPolling()` 共用同一個 `launchPidTicker`，只差寫入 `standardReadings` 還是 `extraReadings`）。

**UI 分組**：40 個標準欄位 + 廠牌專屬欄位全部攤平在同一個格狀清單會太長，新增 `ParameterGroups.kt` 把欄位分成「行車電腦／引擎與動力／溫度／壓力／燃油與排放／電力與診斷／廠牌專屬」七個區塊，做法比照使用者提到的「時速跟轉速已經用一個區塊 group 起來」——沒被明確分類的欄位（也就是任何廠牌 PID）自動歸進「廠牌專屬」，不需要每加一個廠牌就手動維護分類清單。`testDebugUnitTest`／`assembleDebug` 全部通過，實機重新安裝確認開啟不會閃退（沒有真的連上車，還無法實測分組畫面在有資料時的實際呈現）。

### 2026-09-08（第二十三輪：新增手機 GPS 車速，跟 OBD 車速同格比對；回答目前輪詢頻率）

使用者要求在車速方格裡加一個手機 GPS 算出來的車速，字體小一點、跟引擎（OBD）回報的車速放在同一個子區塊比較，更新頻率 1 秒一次。這跟前面所有 PID 資料查證完全不同——GPS 車速不是 OBD 資料，是手機自己的定位硬體，所以獨立設計、不影響現有的 PID 輪詢架構：

- 新增 `gps/GpsSpeedSource.kt`（介面）＋ `AndroidGpsSpeedSource.kt`（用 `LocationManager.GPS_PROVIDER`，不用 Play Services 的融合定位，因為使用者明確要「手機的 GPS」；`minTime=1000ms` 對應「異動時間以一秒為單位」），比照 `BleObdClient` 的做法把 Android 定位 API 包成介面，`DashboardViewModel` 才能維持可單元測試、不直接碰 Android 框架類別。
- `AndroidManifest.xml` 的 `ACCESS_FINE_LOCATION` 原本 `maxSdkVersion="30"`（只是 API 30 以下 BLE 掃描需要定位權限的副作用），拿掉上限，因為 GPS 車速在所有 Android 版本都需要真正的定位權限，跟 BLE 掃描是兩回事。
- `MainActivity` 在 `onStart`/`onStop` 各自呼叫開始/停止追蹤——GPS 只在畫面看得到時才有意義，不像 BLE 連線要為了 Android Auto 共用而整個 process 生命週期都開著。
- Gauge 元件新增 `secondaryValueText` 參數，只有車速那個 Gauge 會帶入 GPS 讀數（`"GPS %.0f".format(...)`），用比主要數字更小的字體、`secondary` 色系顯示在「km/h」單位文字下方，跟原本的 OBD 車速數字放在同一個方格裡。
- 補上 `exposesGpsSpeedAlongsideObdSpeed` 測試（用 fake `GpsSpeedSource`），`testDebugUnitTest`／`assembleDebug` 全部通過，實機重裝後確認狀態列出現定位圖示（代表真的在讀 GPS），室內沒有訊號沒有車速讀數是預期行為，還沒有機會在室外/行駛中實測 GPS 車速跟 OBD 車速的實際落差。

**目前輪詢頻率**（同一輪也回答了使用者的提問）：車速/轉速/MAF（行車電腦用）約 200ms 一輪；標記 `fastPoll: true` 的欄位（檔位、節氣門開度等 7 個）約 800ms 一個；其餘所有標準與廠牌 PID（現在近 40 個標準 + 廠牌專屬）每個 3 秒查一次，清單有幾個欄位就要等幾個 ×3 秒才輪到同一個欄位重問一次。

### 2026-09-08（第二十四輪：GPS 顯示簡化、油耗改 km/L、新增「自訂」區塊＋參數說明、隱藏無故障碼提示、單位中文化）

使用者針對前一輪成果（GPS 車速、行車電腦、全欄位分組）貼了 4 張截圖＋8 點回饋（第 8 點留空），本輪處理第 1–6 點：

1. **GPS 副數字簡化**：拿掉「GPS」文字前綴，直接顯示數字，字級從極小調成 `titleSmall`／`Bold`，位置移到主數字（引擎 OBD 車速）的右下角（`Gauge.kt` 把主數字＋副數字包進同一個 `Box`，副數字用 `Modifier.align(Alignment.BottomEnd)` 疊上去），視覺上維持「引擎數字為主、GPS 數字明顯較小」的比例。
2. **油耗改成公升/公里（km/L）**：使用者上一輪確認過 L/100km 格式，這輪改口要「每公升跑幾公里」——重新檢查發現這其實是同一組原始資料（瞬時油耗率 L/h、車速）換一種除法方向即可，不需要重新設計行車電腦。`updateTripComputer()` 的瞬時/平均油耗改成 `車速 ÷ 油耗率` 得 km/L，車速趨近 0 或油耗率趨近 0 時（同樣有 `MIN_FUEL_RATE_FOR_ECONOMY_LPH`／`MIN_FUEL_FOR_AVERAGE_ECONOMY_L` 門檻避免除以極小值爆出離譜大數）改顯示「公升/小時」瞬時流量，邏輯跟上一輪一樣、只是分子分母互換＋單位字串跟著換。兩個既有測試（`estimatesInstantFuelConsumptionFromMafAndSpeed`／`showsInstantFuelConsumptionInLitersPerHourWhileStationary`）同步改成驗證新的 km/L 數值與字串。
3. **新增「自訂」區塊**：使用者要能自己勾選想優先看到的參數（通用＋廠牌私有都要能選），位置在車速/轉速儀表下方。設計上比照既有 `DeviceMemory`（記住裝置）模式，新增 `CustomSectionStore` 介面＋`SharedPreferencesCustomSectionStore` 實作＋`NoOpCustomSectionStore`（測試/未初始化時的預設值），把選取的欄位名稱集合存進 SharedPreferences 的一個 `StringSet`；`DashboardViewModel` 新增 `allKnownFields`（=通用 PID＋行車電腦欄位＋所有已載入廠牌 profile 的 PID，取 `distinct()`——刻意不寫死清單，這樣以後隨便加一個廠牌 profile，欄位會自動出現在可勾選清單裡，不用兩處維護）與 `selectedCustomFields`，`toggleCustomField()` 負責勾選/取消並立刻持久化。`DashboardScreen.kt` 在儀表卡片下方新增自訂區塊卡片（沒勾選任何欄位時顯示提示文字「尚未選擇任何參數，點右上角編輯圖示新增」），右上角編輯圖示開一個 `CustomFieldPickerDialog` 全螢幕勾選清單，一樣用 `ParameterGroups` 分組顯示。
   - 有個 SharedPreferences 常見陷阱要注意：`putStringSet` 如果直接傳呼叫端還持有參照的可變集合，之後呼叫端改了那個集合會連帶污染已經存進去的偏好設定——`SharedPreferencesCustomSectionStore` 存的時候用 `.toSet()` 做一次防禦性複製。
4. **每個可勾選參數加「i」說明圖示**：新增 `ParameterDescriptions.kt`，用一個 `field -> 繁中說明` 的對照表涵蓋 `universal-obd2.json` 全部欄位、5 個行車電腦衍生欄位、以及目前所有廠牌 profile（Citroën/Peugeot 含 EV DID、Mazda、Ford、Honda）的私有欄位——內容直接沿用這個專案先前已經逐項查證過的技術知識（SAE J1979-DA 正式規格、各廠牌 PID 的原始碼／論壇交叉比對記錄，都已經寫在本文件先前的輪次裡），不是這輪重新上網查的，遇到本來就標記「未驗證/沒有可信資料」的欄位（例如 `gearRaw` 沒有 P/R/N/D 對照表、EV-only 欄位在油車上不適用）在說明文字裡如實反映這個不確定性，沒有查到的欄位一律 fallback 顯示「尚無詳細說明。」而不是編造內容。`CustomFieldPickerDialog` 裡每一行勾選框旁邊加一個小小的 `Info` 圖示按鈕，點下去彈出 `AlertDialog` 顯示該欄位的中文名稱＋說明文字。
5. **無故障碼時完全不顯示提示行**：拿掉 `DashboardScreen.kt` 裡「無故障碼」/ 故障碼清單那整塊 Card（原本沒有故障碼時會顯示一行「無故障碼」文字，有故障碼時額外顯示一張列表卡）；現在唯一的故障碼指示只剩 `TopAppBar` 右上角原本就有的 `BadgedBox`/`Badge` 警示角標，沒有故障碼時角標不出現、也不再有任何一行文字佔位。
6. **溫度單位「度」＋全面單位中文化**：對 `shared/vehicle-profiles/*.json` 的 `unit` 欄位做全面複查，用 `sed` 批次替換：`degC`→`度`（8 個標準欄位＋7 個 Citroën 欄位）、`deg`→`度`（點火正時角度）、`s`→`秒`（行車電腦時間欄位）、`km`→`公里`、`L/h`→`公升/小時`、`L`→`公升`，`citroen-ev.json`／`honda.json`／`ford.json` 也同步套用 `度`／`公里` 替換；確認過改完沒有殘留還在用英文縮寫單位、且沒有動到本來就該保留原文的單位（如 `rpm`、`%`、`V`、`kPa`——這些沒有通用中文慣用縮寫，中文儀表 App 業界也普遍直接沿用原文）。改完的 JSON 照專案慣例同步 `cp` 覆蓋到 `app/src/main/assets/vehicle-profiles/`。

全部 6 項改完 `testDebugUnitTest`／`assembleDebug` 一次就過（新增 `loadsAndPersistsCustomSectionFieldSelection` 測試涵蓋自訂區塊的載入與持久化），`installDebug` 部署到實機後靜態截圖（尚未連車、`掃描中` 狀態）確認：無故障碼提示行確實消失、「自訂」區塊卡片正確出現在儀表下方並顯示空清單提示文字——由於沒有連上車，GPS 副數字位置／中文單位顯示／km/L 油耗換算這幾項還沒有機會在有實際資料的情況下用肉眼複驗，需要下次實際開車測試時再確認。

第 7 點（英文＋繁中雙語系，依手機 OS 語言自動切換）與第 8 點（原始訊息裡是空白，內容不明）這輪還沒有處理——第 7 點是把整個 App 目前散落在 `DashboardScreen.kt`／`PidDisplayNames`／`ParameterDescriptions`／`ParameterGroups`／`DtcDescriptions`／`MainActivity` 等處的大量寫死中文字串，全部改成 Android 標準的 `strings.xml` 多語系資源系統（`values/strings.xml` + `values-zh-rTW/strings.xml`），工程量遠大於前面 6 點的總和，且會牽動幾乎每一個 UI 檔案，決定先在這裡記錄範圍評估、之後跟使用者確認是否要在這個時間點投入，再開始動工，避免倉促做出一半的雙語系。

### 2026-09-11（第二十五輪：iOS 端獨立開發出全新功能後，回頭把可移植的部分補進 Android）

使用者這幾天在另一台 Mac 上用另一個 Claude Code session 獨立開發 iOS 版本（`apps/ios/VLinkerOBD/`），從最初的單一 PID MVP 骨架一口氣做到接近 Android 的完整功能，並帶入 4 個新 commit（`63efc71`／`e8ea69d`／`5667e29`／`f22be1a`）。這輪的任務是讀懂 iOS 這幾輪新增了什麼、逐項判斷能不能／該不該搬回 Android，而不是照單全收——先用一個 Explore 排查 iOS 原始碼列出每個新功能的機制與可移植性，再據此動手。

**v5 schema 遷移（隨 iOS port 一起做的，不是這輪新做的）**：`shared/vehicle-profiles/*.json` 新增 `displayNameZh`／`descriptionZh`／`group`（per-PID）三個欄位，把原本 Android／iOS 兩邊各自維護一份的中文名稱/說明/分組對照表收斂進共用 JSON——Android 這邊 `PidDisplayNames.kt`／`ParameterDescriptions.kt` 已被拿掉，改成讀 JSON 驅動的 `ParameterMetadata.kt`（iOS 端是 `ParameterMetadata.swift`）。確認 `shared/vehicle-profiles/*.json` 與 `apps/android/.../assets/vehicle-profiles/*.json` 逐位元組相同，沒有需要額外同步的資料。

**確認搬過來的功能**：

1. **行車電腦「重連清零」bug 修正**——iOS 原本每次 `startPolling()` 都無條件把 `tripDistanceKm`／`tripFuelLiters`／行駛時間歸零，包含單純訊號中斷後自動重連（`DISCONNECTED_AFTER_ERROR` → 重新掃描 → `READY` → 再跑一次 `startPolling()`）這種本來不該清空的情況。逐行核對後發現 **Android 從第二十二輪 (`startPolling`) 就有一模一樣的 bug**（每次呼叫都無條件重置三個累加欄位）。修法是新增 `pendingTripReset: Boolean`（預設 `true`），只有冷啟動或使用者主動按「中斷連線」（`disconnect()`）才設為 `true`；`startPolling()` 只在這個旗標為真時才歸零，然後立刻清掉旗標——單純的自動重連（`onConnectionStateChanged` 的 `DISCONNECTED_AFTER_ERROR` 分支）完全不碰這個旗標，行駛里程/平均油耗/行駛時間因此撐過短暫斷線。補了 `autoReconnectAfterBleDropoutPreservesTripComputer`／`explicitDisconnectResetsTripComputerOnNextConnect` 兩個測試鎖住這個行為（跑 30 個 fast-loop tick 累積出有意義的里程數，再分別模擬「斷線後自動重連」與「使用者主動斷線後重連」，驗證前者延續累積、後者才真的歸零）。
2. **行車電腦時間改用實際時鐘**——原本 `tripElapsedSeconds` 是每個 tick 手動 `+= POLL_INTERVAL_MS/1000.0` 累加，如果輪詢的 coroutine 曾經被系統暫停過一段時間（例如 Android Doze），這個累加值會悄悄跟不上真實經過的時間。改成記錄 `tripStartTimeMs`（`System.currentTimeMillis()`），每次都用 `(現在時間 - 起始時間)` 算出行駛時間，不管中間輪詢迴圈有沒有被暫停過都準確。沒有新增測試直接鎖定這個換算（虛擬時間的 `TestDispatcher` 不會推進真實系統時鐘，用真實時鐘反而沒辦法用虛擬時間測試——這點跟 iOS 用 `Date()` 的簡單做法一致，兩邊都沒有為這個時鐘抽象加測試）。
3. **語音播報**——新增 `speech/SpeechAnnouncer.kt`（介面＋`NoOpSpeechAnnouncer`）＋`AndroidSpeechAnnouncer.kt`（包 `android.speech.tts.TextToSpeech`，語言用 `Locale.TAIWAN`，取不到繁中語音包時退回簡中；`AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` 讓播報像導航 App 一樣會 duck 掉正在播放的音樂，對應 iOS 用 `AVAudioSession` 的 `.voicePrompt`/`.duckOthers`）。播報兩件事，時機比照 iOS：電池電壓在每次連線後第一次讀到 `controlModuleVoltage` 時念一次（例如「電池電壓 12.6 伏特」），檔位在每次變檔（`gearRaw` 讀值改變，且不是這次連線的第一筆基準值）時念一次（例如「已跳4檔」）。跟 iOS 一樣**沒有開關**——照 iOS 程式碼註解的說法，這兩件事夠稀少、夠短，正是「開車時可以不看螢幕直接聽」值得為它中斷正在播的音樂的資訊。兩個旗標 (`hasAnnouncedBatteryVoltage`／`lastAnnouncedGear`) 在每次 `startPolling()`（包含自動重連）時重置——這點特意跟行車電腦的重置邏輯分開處理，重連後重新播報一次電壓沒有壞處，不像行車電腦歸零會抹掉真實累積的里程。補了 `announcesBatteryVoltageOncePerConnection`／`announcesGearChangesButNotTheFirstBaselineReading` 兩個測試。
4. **GPS 車速降級顯示**——原本 OBD 車速逾時（連續 5 次輪詢失敗，`speedStaleCount >= STALE_THRESHOLD`）時，主要車速數字只會顯示 `vehicleData.speedKph` 的 fallback 0（等於斷線時直接歸零顯示）。改成 `obdSpeedKph ?: gpsSpeedKph ?: 0f`：OBD 車速消失但手機 GPS 還有訊號時，主要數字改顯示 GPS 車速，單位文字也從「km/h」換成「GPS訊號」提醒使用者這是替代來源；原本右下角的 GPS 比對小數字這時候會隱藏（兩個都顯示 GPS 車速沒有意義）。純粹是 `DashboardScreen.kt` 的顯示邏輯調整，`DashboardViewModel`／`GpsSpeedSource` 完全沒動，因為 Android 本來就已經有 GPS 車速的資料管線（第二十三輪做的）。
5. **浮動車速小視窗**——iOS 用 `AVPictureInPictureController` 搭配假造影格的方式做出一個能浮在其他 App 上方的小視窗；Android 有原生對應功能（`Activity.enterPictureInPictureMode`，`minSdk 26` 剛好滿足 API 26 需求），不需要影格模擬那一套，直接讓系統把目前畫面縮小即可。新增 `ui/PipSpeedView.kt`（只顯示大大的車速數字＋單位，OBD/GPS 降級邏輯跟主畫面一致）、`MainActivity` 新增 `enterSpeedPipMode()`（`PictureInPictureParams` 設 3:2 長寬比）與 `onPictureInPictureModeChanged` 覆寫（用一個 `mutableStateOf` 切換 `setContent` 要渲染 `DashboardScreen` 還是 `PipSpeedView`），overflow 選單加一個「開啟浮動車速視窗」項目。`AndroidManifest.xml` 幫 `MainActivity` 加上 `android:supportsPictureInPicture="true"` 與 `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|uiMode"`——後者特別重要：沒有宣告的話，進出 PiP 視窗本身就是一次 Activity 設定變更，預設行為是整個 Activity 重建，會重新觸發第二十一輪那個「旋轉導致 Activity 重建、重跑 BLE 自動連線 LaunchedEffect、弄壞連線狀態」的舊 bug——這正是當初鎖定 portrait 想避開的同一類問題，這次用 `configChanges` 直接聲明「這些變更自己處理，不要重建」來解決，而不是重蹈覆轍。

**確認不搬的功能**：
- **CarPlay 場景骨架**（`CarPlaySceneDelegate.swift`）——iOS 程式碼自己的註解就寫明「這段程式碼本身不會讓 App 真的上 CarPlay，要等 Apple 核准對應的 entitlement，這個類別實際上永遠不會被實例化」，是純骨架、目前不會執行任何東西。Android 這邊本來就有功能對等且已經真的能用的 Android Auto 整合（`car/VLinkerCarAppService` 等，第十四輪做的），沒有東西需要補。
- **雙儀表板／landscape 同心圓環形錶＋分組滑頁**——iOS 最後把兩種模式合併成「只有 landscape 環形錶」一種畫面，跟 Android 的 portrait-only 鎖定方向正好相反。Android 目前鎖 portrait 是為了閃避一個已知的旋轉→BLE 連線損毀 bug（第二十一輪），要做 landscape 版面等於要先解決那個根因問題，不是這輪能力範圍內的小改動，列為之後有需要再評估的待辦，這輪不動。

全部 4 項改完（含 4 個新測試），`testDebugUnitTest`／`assembleDebug` 一次就過，但這輪收尾時手機沒有透過 `adb` 連線（`adb devices -l` 回傳空清單），沒有機會做實機截圖驗證——語音播報、GPS 降級顯示、PiP 浮動視窗這三項都還沒有在真實裝置上肉眼/耳朵確認過，需要手機重新連線後再測。

**事後複查（同一輪，使用者要求「recheck all function and test properly」）**：除了重跑 `clean testDebugUnitTest assembleDebug`（146 個測試全過，非快取結果）之外，額外做了兩項平常沒做過的檢查：

1. **驗證新測試真的有鎖住 bug，不是空判斷**——暫時把 `startPolling()` 的 `if (pendingTripReset)` 改回無條件重置（模擬修復前的舊行為），重跑 `autoReconnectAfterBleDropoutPreservesTripComputer`：如預期失敗，證明這個測試真的會在 bug 重新出現時抓到；`explicitDisconnectResetsTripComputerOnNextConnect` 兩種寫法下都通過（本來就該如此，這個案例的行為在改之前之後是一致的）。確認完立刻改回正確版本，重跑全套測試恢復全綠。
2. **補跑 `lintDebug`**（先前的驗證流程只有 `testDebugUnitTest`／`assembleDebug`，沒有納入 lint）——抓到兩個問題並修正：
   - `CoarseFineLocation`（lint 錯誤等級，導致 `lintDebug` 直接 fail）：第二十三輪加 GPS 車速功能時只宣告了 `ACCESS_FINE_LOCATION`，沒有一併宣告 `ACCESS_COARSE_LOCATION`——Android 12 開始，只要 App 要 FINE 權限就必須兩個一起宣告＋一起請求，系統的「精確／大概位置」選擇對話框才會正常顯示。補上 manifest 的 `ACCESS_COARSE_LOCATION` 宣告，並把 `MainActivity` 的 `locationPermissionLauncher` 從 `RequestPermission()`（單一權限）改成 `RequestMultiplePermissions()`，一次請求兩個，但仍然只檢查 FINE 有沒有被授予才啟動 GPS 追蹤（`GPS_PROVIDER` 只有 FINE 授權才能用，COARSE 不夠）——這個 bug 是第二十三輪就存在的舊債，不是這輪新增的。
   - `ModifierParameter`（Compose 官方慣例：`modifier: Modifier` 應該是函式簽章裡第一個有預設值的參數，這樣呼叫端才能穩定用位置參數＋trailing lambda 語法）：這輪在 `DashboardScreen()` 簽章裡插入 `onEnterPipMode: () -> Unit = {}` 時插在 `dtcDescriptions`／`parameterMetadata`（兩者都沒有預設值）跟 `modifier` 之前，導致 `modifier` 不再是「第一個有預設值的參數」。改成把 `onEnterPipMode` 移到 `modifier` 後面（簽章最後一個參數）即修正——這個是這輪新增程式碼自己造成的，不是舊債。

兩個修正後重跑 `lintDebug testDebugUnitTest assembleDebug` 全部通過，警告數從 15 降到 14（剩下的 14 個全部是既有、跟這次改動無關的項目：8 個 Gradle 依賴版本可升級提示、`android:screenOrientation="portrait"` 的兩個對應警告——這是刻意的設計決策，第二十一輪已經記錄過取捨原因、Android Auto 服務的 `ExportedService`、圖示資料夾的 `ObsoleteSdkInt`、`BLUETOOTH_SCAN` 的 `usesPermissionFlags` 這輪 minSdk 用不到的屬性——只有一個新警告 `PictureInPictureIssue` 是這輪 PiP 功能帶來的：Android 12+ 建議額外呼叫 `setAutoEnterEnabled`／`setSourceRectHint` 讓 PiP 進出動畫更順滑，純粹是視覺體驗上的加分項而非功能缺陷，這輪先不做，留待之後真的要打磨 PiP 體驗時再處理。手機仍未連線，實機驗證（含這次修正的定位權限對話框）依然是待辦。

### 2026-09-14（第二十六輪：iOS 端自己跑了一次 ultrareview 抓到的 bug，逐項核對 Android 是否也中招）

使用者要求「check the design from github and review this android version」。拉取後發現 Mac 那邊多推了一個新 commit（`60ef562`，"fix(ios): ultrareview fixes, CarPlay Dashboard, gear P/R mapping, reorderable pickers"）——iOS 端對自己上一輪（第二十五輪對應的那批功能：行車電腦重連保留、GPS 降級、PiP、語音播報）跑了一次 code review，抓到好幾個自己程式碼裡的 bug 並修正。由於 Android 這輪是照著 iOS 當時（修正前）的行為去移植的，這些 bug 有沒有跟著抄過來，得逐項核對，不能假設「因為架構不同所以沒事」。

逐項核對結果：

1. **`connect()` 無條件重置行車電腦**——iOS 原本的修正把重置邏輯掛在 `connect()` 上，結果連「自動重連」路徑也會呼叫到 `connect()`，等於重置邏輯繞了一圈又繞回原本想避免的 bug；改成用 `isUserInitiated` 參數區分。**核對 Android：沒有中招**——Android 的 `pendingTripReset = true` 是設在 `disconnect()`（使用者主動斷線）裡，`connect()` 完全沒有碰這個旗標，架構上跟 iOS 修正後的版本是同一個設計，不是巧合躲過而是本來就這樣寫的。
2. **GPS 降級速度餵進加速度計算，OBD 舊讀值 vs GPS 新讀值互減，產生假的急加速/煞車尖峰**——**核對 Android：沒有中招，但只是因為 Android 根本還沒做這個功能**——第二十五輪 Android 的 GPS 降級只做在 UI 顯示層（`DashboardScreen.kt` 的儀表數字），行車電腦（`updateTripComputer`）完全沒有接上 GPS，OBD 訊號中斷時里程/油耗會直接停止累積。這是真正的功能缺口，這輪照 iOS 修正後的正確做法一起補上：`updateTripComputer` 拆成 `speedKph`（OBD 優先，斷訊時退回 GPS，餵給里程/油耗/油耗經濟性計算）與 `obdSpeedKph`（純 OBD，斷訊時是 `null`，只餵給加速度計算），兩個值互相independent，加速度永遠不會拿 GPS 數字去跟 OBD 數字互減。新增 `tripDistanceFallsBackToGpsSpeedWhileObdSpeedIsStale` 測試鎖住這個行為。
3. **加速度用假定的固定輪詢間隔（`POLL_INTERVAL_MS`）當分母，而不是兩次取樣間實際經過的時間**——**核對 Android：真的中招了**。Android 的 `fastLoopPaused`（手動指令、ECU 測試、每一次廠牌/標準 PID 輪詢都會短暫借用這個旗標暫停快速迴圈）代表兩次真正拿到 OBD 車速讀值之間，實際經過的時間不見得剛好是 200ms，可能因為輪詢排隊而拉長到一兩秒——用固定 200ms 去除速度差，遇到這種情況會算出離譜的加速度尖峰。修正：新增 `previousSpeedSampleTimeMs`，改用 `(現在時間 − 上次取樣時間)` 當真實分母，並比照 iOS 加上 `dtSeconds > 0 && dtSeconds < 2.0` 的合理範圍檢查——超出範圍就這一拍先不發佈新的加速度數字（維持上一個有效值），不會拿一個橫跨斷點的差值硬算出天文數字。
4. **PiP 的 teardown 在 `stop()` 裡直接同步執行，沒有等系統的 delegate callback 確認真的停止**、**PiP 啟動被系統拒絕時 `isActive` 永遠卡在 true**——**核對 Android：架構上完全不會中招**。iOS 的 PiP 是自己手刻 `AVSampleBufferDisplayLayer` 塞假影格進 `AVPictureInPictureController`，所以才需要自己管理啟動/停止的狀態機。Android 這邊用的是系統原生 `enterPictureInPictureMode()`，`isInPip` 這個狀態完全是從系統的 `onPictureInPictureModeChanged` callback 被動驅動、從來不會在呼叫 `enterPictureInPictureMode()` 當下就樂觀地先設成 true——沒有自己手刻的 teardown 邏輯，也就沒有「同步 teardown 搶在 callback 前面跑」或「啟動被拒絕卡死」這兩類 bug 的容身之處，這正是選用系統原生 API 而不是複製 iOS 那套手刻方案的好處。
5. **`SpeechAnnouncer` 的音訊設定旗標即使設定失敗也會 latch 住，永遠不會重試**——**核對 Android：檢查過，設計上不對等，不強行套用同一個修正**。iOS 的問題是 `AVAudioSession.setActive(true)` 這個呼叫本身可能拋錯（例如有電話正在進行），錯了卻用 `try?` 吞掉還照樣 latch 成功旗標。Android 這邊 `AndroidSpeechAnnouncer` 用的是 `TextToSpeech` 的非同步 `OnInitListener`，`ready = true` 只有在 `status == TextToSpeech.SUCCESS`（引擎真的初始化成功）之後才會設定；後面的 `setLanguage()`／`setAudioAttributes()` 即使繁中語音包不存在，最多退化成用系統預設語言講話，不會整個啞掉——跟 iOS「整個 session 沒 active、完全發不出聲音」的失敗模式嚴重程度不同，這輪不跟著改。
6. **檔位 P/R 對照（0=P、7=R）**——iOS 這輪根據自排車主的實測回報，正式把這個對照表寫進 `citroen.json` 的 `descriptionZh`，並在畫面格式化與語音播報都加上特殊處理。這份 JSON 的更新（純文字說明，公式沒變）已經在 `git pull` 的時候自動帶進 `shared/`／Android assets 兩邊（確認過兩份 byte-for-byte 相同）。**這輪把對應的程式邏輯也搬進 Android**：新增 `formatGearDisplay()`（0→「P 檔」、7→「R 檔（倒車）」、其餘維持「N 檔」的整數顯示，不再是舊的 `"%.1f 檔"` 帶小數點格式）套用在 `launchPidTicker` 的格式化字串上；`gearAnnouncement()`（0→「P 檔」、7→「倒車檔」、其餘維持「已跳N檔」）套用在語音播報。新增 `formatsAndAnnouncesParkAndReverseGearsSpecially` 測試同時鎖住畫面格式與語音兩邊；連帶更新了舊的 `fastPollBrandPidIsPolledMuchSoonerThanTheNormalBrandTicker` 測試，把過期的 `"3.0 檔"` 斷言改成新格式的 `"3 檔"`。
7. **自訂區塊／指針錶圖例的欄位選擇器改成只列出目前偵測到／選擇的廠牌欄位，不再是「所有已載入過的廠牌」（移除 `allKnownFields` 這個「已死」欄位）**——這是 iOS 這輪順手做的設計簡化：先前的 `allKnownFields` 一次性算好、包含所有已載入的廠牌 profile 的欄位，切換／偵測到不同廠牌後也不會跟著收斂，導致挑選器裡永遠混著其他廠牌、這台車不可能回答的欄位。**Android 有一模一樣的設計問題**（`allKnownFields` 原本是建構時算一次的 `val`，`selectBrand()` 從來沒有重新算過），這輪一併修正：把 `allKnownFields` 改成 `relevantFieldsFor(brand)` 函式（通用＋行車電腦欄位＋該廠牌自己的欄位），`selectBrand()` 呼叫時連帶更新 `_uiState.allKnownFields`。原本斷言「一建立 ViewModel 就看得到 Honda 的 `batteryVoltage`」的舊測試已經不成立（那正是這個 bug 本身的行為），改寫成驗證「選擇 Honda 之前看不到、選了之後才看得到」，直接鎖住修正後的行為而不是舊的錯誤行為。

**確認不需要處理的其餘項目**：
- **CarPlay Dashboard 場景骨架**（`CarPlayDashboardSceneDelegate.swift`）——跟主要的 CarPlay 場景骨架同樣的「等 Apple 核准 entitlement 之前不會被實例化」的狀態，Android Auto 已有功能對等且真正能用的整合，不需要對應動作。
- **`selectedCustomFields` 從 Set 改成有序陣列＋側邊面板拖曳排序、雙擊開啟選擇器**——這是 iOS 特定 UI（landscape 環形錶的側邊固定卡片）的排序需求；Android 的「自訂」區塊目前是格狀清單而非側邊固定卡片，沒有對應的排序 UI 概念，這輪不動，等 Android 真的要做類似排版時再評估是否要跟進。
- **主畫面名稱改成「平安行車通」**（iOS `CFBundleDisplayName`）——純品牌命名決定，不是程式邏輯，留給使用者決定要不要讓 Android 這邊（目前 manifest 是「行車通」）也跟著改名，這輪不擅自更動。

全部改完後 `clean testDebugUnitTest assembleDebug lintDebug --rerun-tasks` 一次全過（148 個測試、0 失敗，lint 沒有新增問題）。手機這輪同樣沒有透過 `adb` 連線，這批修正（尤其是 GPS 降級餵進里程／加速度真實時間差）都還沒機會實機驗證。

**重大更新（同一輪，手機重新連上後實機測試）**：手機（CPH1877）重新連上後裝上這批修正，一開機就閃退——`adb logcat` 抓到 `java.lang.AbstractMethodError: abstract method "void android.location.LocationListener.onProviderDisabled(java.lang.String)"`。

根因：`gps/AndroidGpsSpeedSource.kt`（第二十三輪 GPS 車速功能寫的）用 Kotlin 的 SAM 轉換寫法 `LocationListener { location -> ... }`，只實作 `onLocationChanged`，仰賴 `LocationListener` 介面在較新 API 版本裡幫 `onStatusChanged`／`onProviderEnabled`／`onProviderDisabled` 提供的預設空實作。問題是**這支實機（OPPO ColorOS）雖然回報的 API level 滿足 compileSdk 需求，實際上機的 framework 並沒有帶那三個方法的預設實作**——這是已知的、特定 OEM ROM 會出現的相容性陷阱：編譯期用的 SDK stub 有預設方法，執行期的實際 framework 卻沒有，一呼叫就丟 `AbstractMethodError`。這顆 bug 從第二十三輪加入 GPS 功能後就一直潛伏著，**這是這整個專案第一次真正抓到、修好的一個「一開啟 GPS 追蹤就必定閃退」的嚴重 bug**——回頭看，先前好幾輪的實機驗證screenshot都只拍到剛啟動、GPS 還沒真正開始追蹤前的畫面，沒有一次撐到閃退發生後還繼續操作，所以整個抓漏過程都沒發現。

修法：把 `listener` 從單一 lambda 改成 `object : LocationListener { ... }` 明確覆寫全部四個方法（`onLocationChanged` 做正事，其餘三個明確給空的方法主體），這樣不管執行期的 framework 有沒有預設方法，這個類別的 bytecode 本身就一定有完整實作，不會再依賴執行期的介面預設方法解析行為。

修好後在實機上完整驗證了這一輪所有新功能：
- App 正常啟動、不再閃退，BLE 掃描正常運作
- 開啟 overflow 選單，確認新增的「開啟浮動車速視窗」選項存在
- 點擊後，App 真的縮小成一個浮動在桌面上的小視窗，顯示「--」（尚未連線、沒有速度資料，符合預期的誠實 fallback 顯示）與「km/h」單位文字，右上角有關閉按鈕——PiP 功能端到端驗證成功
- 關閉浮動視窗後，`logcat` 沒有任何新的錯誤/例外

語音播報、GPS 降級主顯示、行車電腦重連保留這幾項功能因為沒有實際連上車（沒有 OBD 資料、車子沒在開），還沒機會用真實駕駛情境驗證，留待下次開車測試時確認。

### 2026-09-14（第二十七輪：追加一輪 iOS commit、全面地毯式比對，並處理 App 改名／螢幕常亮）

**追加的 iOS commit**（`8d3829c`，"simplify gear voice announcement, add tire-pressure diagnostics probe"）：
1. 檔位語音播報簡化——前進檔位只念「2檔」，不再是「已跳2檔」（P/R 維持原本各自的措辭）。Android 同步調整 `gearAnnouncement()`，並更新對應測試斷言。
2. 新增「查詢胎壓原始值」一鍵診斷探測按鈕（跟上一輪 `probeGearRaw()` 同一套 `sendCommandSequence` 連續送出機制，探測 4 個胎壓 DID：`22D610`／`22D60F`／`22D612`／`22D611`，header `6AF`）——這 4 個 DID 從來沒有回過資料，這顆按鈕能一次分辨「NO DATA（模組在，但 DID 不對）」還是「逾時（車上可能根本沒裝胎壓偵測模組——這台車的原廠維修手冊把它列為選配）」。連帶發現 Android 原本只有 `sendManualCommand`（一次一條指令），沒有 iOS 這套「一次連續送出多條指令、只暫停快速迴圈一次」的機制——單條慢慢打，指令間的空檔在高速行駛時足以讓 ECU/匯流排閒置，最後一條常常錯誤地收到 NO DATA。這輪把 `sendManualCommand` 重構成建立在新的通用 `sendCommandSequence(commands: List<String>)` 之上，並新增 `probeGearRaw()`／`probeTirePressures()`，兩個都掛進「診斷主控台」對話框，各一個按鈕。

**使用者接著要求「檢查 GitHub 是否沒有抓到全部的 iOS 程式做 Android 的功能開發」**——用一個 Explore 排查，不是只比對最近幾個 commit 的 diff，而是把 `apps/ios/VLinkerOBD/*.swift`（排除 landscape 環形錶那組、CarPlay 骨架這兩類已確認不需要搬的）跟 Android 對應程式碼逐檔案、逐行核對一遍。結論：**大部分已經搬得非常完整**（BLE 層、`ObdCommandQueue`、`ObdResponseParser`、`PidFormula`、`BitFieldExtractor`、`VehicleBrandDetector`、`ParameterMetadata`、`DtcParser`／`DtcDescriptions` 幾乎逐行對應），只抓到 4 個先前沒發現的小落差：

1. **自訂區塊少一個「全部移除」按鈕**——iOS 的 `CustomFieldPickerView` 有一個一次清空全部已選欄位的按鈕（`DashboardController.clearAllCustomFields()`）。Android 新增 `DashboardViewModel.clearAllCustomFields()` 與 `CustomFieldPickerDialog` 標題列上的「全部移除」按鈕（紅字，`role=destructive` 對應的視覺處理）。
2. **首次開啟沒有預設的自訂欄位**——iOS 的 `ParameterGroups.defaultCustomFields`（水溫、油量、電瓶電壓、引擎負載、瞬時/平均油耗、行駛里程、環境溫度 8 個通用欄位）在使用者從沒動過選擇器時當作預設值，讓第一次開啟就看到有意義的資料，而不是空白區塊；`UserDefaultsCustomSectionStore` 用「這個 key 有沒有被寫過」而不是「陣列是不是空的」來分辨「從沒設定過」跟「使用者按了全部移除、故意留空」。Android 補上對等的 `ParameterGroups.DEFAULT_CUSTOM_FIELDS`，`SharedPreferencesCustomSectionStore.selectedFields()` 改用 `prefs.contains(KEY_FIELDS)` 做同樣的「從未設定 vs 故意清空」判斷（這個修正也讓第 1 點的「全部移除」按鈕不會在下次開啟時被悄悄復原成預設值）。
3. **`runtimeSinceStartSec` 沒有格式化**——這個欄位（ECU 距離上次啟動的累計運轉秒數，某些 ECU 的這個計時器從來不會重置，可能累積到幾十小時）原本跟其他欄位共用 `"%.1f %s"`（例如「146441.0 秒」），完全不可讀。iOS 有 `formatHoursMinutes()` 把它轉成「40 小時 41 分鐘」這種格式；Android 補上同名邏輯的 `formatHoursMinutes()`，比照 `gearRaw` 的方式在 `launchPidTicker` 特殊處理這個欄位。
4. **沒有保持螢幕常亮**——iOS 的 `ContentView.swift` 設定 `UIApplication.shared.isIdleTimerDisabled = true`，手機掛在車上開著這個 App 不會自動鎖屏。Android 完全沒有對應設定，螢幕逾時鎖屏後整個「開車時一瞥即知」的用途就失效了——這個雖然沒被 Explore 列進三個「headline gaps」，但判斷上跟行車安全/核心可用性關係更大，這輪直接一起加上：`MainActivity.onCreate()` 呼叫 `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)`。

四項都已實作、新增/更新對應測試，`testDebugUnitTest assembleDebug lintDebug` 全過。

**App 改名**：使用者確認 iOS 那邊已經把主畫面名稱改成「平安行車通」（`CFBundleDisplayName`），這輪不再是「留給使用者決定」的開放項目——直接把 Android 對應的三處都改掉：`AndroidManifest.xml` 的 `android:label`（啟動器圖示下方名稱）、`DashboardScreen.kt` 頂端列標題文字的預設值（沒偵測到廠牌時顯示的品牌名稱）、`car/DashboardCarScreen.kt`（Android Auto 車機畫面的標題）。

**關於「沒有預設變成橫式畫面，判斷是存在舊的 App 功能」**——這不是舊程式碼殘留，是第二十一輪就記錄下來的刻意設計：Android 鎖定 portrait 是為了閃避一個真實存在、已經修好但還沒解除鎖定的 bug（旋轉觸發 Activity 重建，重跑 BLE 自動連線的 `LaunchedEffect`，弄壞連線狀態）。iOS 那邊在第二十五輪把兩種模式（portrait 標準模式／landscape 座艙模式）直接砍成只剩 landscape 環形錶一種畫面，等於两邊現在的方向鎖是**相反的**，不是 Android 落後或殘留舊功能。要不要讓 Android 也做橫式（不管是解鎖 portrait+landscape 雙向支援，還是像 iOS 一樣整個改成 landscape-only 的環形錶版面）是一個規模不小的獨立決定——需要先重新設計旋轉時的 BLE 連線狀態管理避免重蹈舊 bug，再決定要不要順便做一版 Android 自己的環形錶／滑頁版面（照搬 iOS 的 `DrivingDynamicsDashboardView`／`GroupPaging`／`RingLegendFieldPickerView`，或維持 Android 現有的指針錶＋格狀清單）。這輪先把這個判斷記錄下來、不擅自動工，等使用者明確決定要不要投入再開始規劃。

### 2026-09-14（第二十九輪：landscape 環形錶儀表板全面移植——使用者貼出 iOS 實際畫面截圖，確認要做）

使用者貼出 iOS 目前的 landscape「座艙模式」實際截圖，明確要求把這個畫面搬到 Android，並在後續訊息追加兩項要求：畫面**只**保留橫式（不要 portrait+landscape 雙向）、側邊卡片要能拖拉排序＋連點兩下開啟參數選擇器（iOS 都已經做了）。這是這個 session 目前規模最大的一輪——完整逐檔案精讀 iOS 的 `DrivingDynamicsDashboardView.swift`／`GaugeMath.swift`／`GroupPaging.swift`／`DashboardDialogs.swift`（`RingLegendFieldPickerView`）／`PersistenceStores.swift`（`RingLegendFieldsStore`），照著同樣的機制、配色、佈局在 Android 上重建，而不是憑截圖臆測畫面細節。

**新增檔案**：
- `ui/GroupPaging.kt`＋`GroupPagingTest.kt`——把小分組合併成同一頁、大分組獨佔一頁的純函式，逐字對照 iOS 的 `GroupPagingTests.swift` 搬了 5 個測試。
- `ui/gauge/DesignPalette.kt`——iOS `DesignPalette` enum 的顏色逐一對照搬過來（accent 青色、speedOrange 橘色車速、rpmNormal 紫色轉速、warn/danger 分段變色、背景色）。
- `ui/gauge/DrivingRingGauge.kt`——雙圈環形錶（外圈車速、內圈轉速），沿用既有 `GaugeMath.valueToAngleDegrees`（Android/iOS 兩邊本來就用同一套角度換算慣例），Canvas 直接畫弧（`drawArc`，比 iOS 手動分段畫路徑的寫法更簡單，Android 原生 API 本來就支援圓角弧線筆畫）；5 秒峰值保持標記（一個指向刻度掃描方向的小三角形箭頭＋數字，數字用 `nativeCanvas`/`Paint` 畫，因為 Compose 的 `DrawScope` 沒有內建文字繪製 API）；中央大數字＋GPS 降級文字/比對行＋使用者自選的兩個中央圖例欄位。
- `ui/GlassStatCard.kt`——側邊卡片與分頁格線共用的毛玻璃卡片元件，圖示＋數值＋標籤。
- `ui/DrivingDynamicsDashboardScreen.kt`——整個新畫面：頂部列（連線狀態、品牌/VIN、故障碼角標、選單）、`HorizontalPager` 分頁（第 0 頁是儀表主頁：左右各 4 張自訂欄位卡片＋中央環形錶；其餘頁面是 `GroupPaging` 合併後的參數分組，`LazyVerticalGrid` 3 欄格線）、頁面指示點、新增的「選擇中央顯示參數」2 選 1 對話框（FIFO 淘汰邏輯跟 iOS 一致：選第 3 個會把最早選的那個換掉）。
- `ui/RingLegendFieldsStore.kt`／`SharedPreferencesRingLegendFieldsStore.kt`——比照 `CustomSectionStore`／`SharedPreferencesCustomSectionStore` 的既有模式，存最多 2 個欄位（用逗號分隔字串存，因為順序有意義，`SharedPreferences` 的 `StringSet` 不保序）。

**改動既有檔案**（把原本 portrait-only 的一些私有元件放寬成可跨檔案重用，避免重複實作）：
- `DashboardScreen.kt` 的 `iconForField`／`SectionLabel`／`DiagnosticsDialog`／`TroubleCodeDetailDialog`／`DevicePickerDialog`／`EcuSupportTestDialog`／`CustomFieldPickerDialog` 從 `private` 放寬成可見，讓新畫面直接重用，不必重寫一份幾乎一樣的對話框。
- `DashboardViewModel.kt` 新增 `ringLegendFieldsStore` 建構參數與 `toggleRingLegendField()`（2 槽 FIFO 邏輯）。
- `ParameterGroups.kt` 新增 `DEFAULT_RING_LEGEND_FIELDS = listOf("coolantTempC", "fuelLevelPercent")`，對應 iOS 的 `defaultRingLegendFields`。
- `AndroidManifest.xml`／`MainActivity.kt`：`android:screenOrientation` 改成 `"sensorLandscape"`（兩個橫向皆可、依手機實際掛法自動決定，但絕不會是 portrait）——這是使用者第二則訊息明確要求「只留橫式」後才加的；`configChanges` 沿用第二十八輪已經加好的宣告（涵蓋 orientation），確保鎖定橫式不會重踩「旋轉觸發 Activity 重建」的舊坑。`MainActivity` 拿掉原本的 `isLandscape` 條件判斷，永遠渲染 `DrivingDynamicsDashboardScreen`（PiP 模式除外）。

**刪除死碼**：既然 portrait 畫面已經不可能被觸發，原本的 `DashboardScreen()`（整個 portrait 指針錶＋格狀清單版面主函式）連同只有它在用的 `StatusHeader`／`ParameterStatCard` 一併刪除，而不是留著沒人呼叫的程式碼——先用 grep 確認這幾個符號除了自己以外沒有其他呼叫端才刪，並清掉隨之產生的一批不再需要的 import（`TopAppBar`／`Scaffold`／`ExperimentalMaterial3Api`／`Gauge`／`TrendChart`／`GridCells`／`LazyVerticalGrid` 等）。

**拖拉排序（Set→List 資料層搬遷）**：使用者第二則訊息明確要求要拖拉排序，這代表 `selectedCustomFields` 不能再是無序的 `Set<String>`（Android 原本的設計），因為側邊卡片的左右分邊、上下順序現在需要一個穩定、使用者可控的順序才有意義（iOS 一直是 `[String]` 有序陣列）。搬遷範圍：
- `CustomSectionStore` 介面／`NoOpCustomSectionStore`／`SharedPreferencesCustomSectionStore`：`Set<String>` 全部改 `List<String>`；後者的實際儲存格式從 `putStringSet` 改成逗號分隔字串（原因同 RingLegendFieldsStore：順序有意義，`StringSet` 不保序）——**刻意換了一個新的 SharedPreferences key**（`selected_fields_ordered`，不是沿用舊的 `selected_fields`），因為如果沿用舊 key、用 `getString` 去讀一個曾經用 `putStringSet` 寫入的值，會直接丟 `ClassCastException` 閃退；這支 App 還沒上架，舊資料重置一次是可接受的代價，不值得為了保留舊資料另外寫遷移邏輯。
- `DashboardUiState.selectedCustomFields`：`Set<String>` → `List<String>`。
- `DashboardViewModel`：`toggleCustomField()`／`clearAllCustomFields()` 幾乎不用改（Kotlin 的 `List` 也支援 `+`/`-` 運算子，語意剛好符合「新選的欄位加到最後面」），新增 `moveCustomField(field, target)`（搬到 target 前面，複製 iOS `DashboardController.moveCustomField` 的邏輯：兩者相同或任一個不在清單裡就不做事）。
- `DrivingDynamicsDashboardScreen.kt` 的 `SidePanel`：改成長按後拖曳（`detectDragGesturesAfterLongPress`），卡片跟著手指垂直位移，放開時依照拖曳距離／卡片高度換算出目標位置，只在放開的那一刻呼叫一次 `onMoveCustomField`（不是拖曳過程中連續呼叫）——因為 Compose 穩定版 Foundation API 沒有 SwiftUI `.draggable`/`.dropDestination` 那種系統級手勢，這段是手刻的、Compose 生態系常見的「長按拖曳排序」寫法，不是官方套件。連點兩下開啟參數選擇器（`detectTapGestures(onDoubleTap=...)`）沿用上一輪已經做好的邏輯，跟拖曳手勢用兩個獨立的 `pointerInput` 修飾子疊加在同一張卡片上。

**修正一個實機才會發現的排版 bug**：橫式螢幕在這支測試機上實際高度只有約 360dp（換算自 1080px÷3x 密度），4 張堆疊的側邊卡片原本的尺寸（padding/字體）加起來會超出可用高度，導致最後一張卡片的文字被底部邊界硬生生切掉（截圖親眼看到「行駛里程」被切成「仁馳里程」的殘影）。修法雙管齊下：`GlassStatCard` 的 `compact` 模式縮小 padding/圖示/字體，讓 4 張卡片在大多數機型上不需要捲動就放得下；`SidePanel` 加上 `verticalScroll` 當作保險——就算某些螢幕真的放不下 4 張，也是可以捲動看到，不會再無聲裁切。

**實機驗證**：手機重新連上後完整測試——App 啟動不閃退（此時手機物理上還是直放，`android:screenOrientation="sensorLandscape"` 直接把顯示器強制轉成橫向，證實鎖定確實生效，不需要使用者自己動手轉）；截圖比對跟使用者貼的 iOS 參考圖非常接近：頂部列（掃描狀態／App 名稱／選單）、環形錶（車速數字＋單位＋水溫/油量中央圖例）、左右各 4 張自訂卡片（水溫／油量／電瓶電壓／…、瞬時油耗／平均油耗／行駛里程／…）、底部頁面指示點，排版修正後卡片文字完整不再裁切；`adb` 模擬雙擊（不管是兩次分開的 `input tap` 還是同一個 shell 呼叫裡連續兩次）都沒能觸發參數選擇器開啟，但過程中也沒有任何閃退或錯誤——這比較可能是 `adb shell input tap` 本身對 Compose 雙擊時間窗的模擬不夠精準（這是 adb 腳本化測試手勢的已知限制，不是實機真手指點擊的可靠測試方式），拖曳排序同樣沒有機會用 adb 可靠地模擬。**雙擊開啟選擇器、長按拖曳排序這兩個手勢互動，都還需要使用者親自用手指測試才能真正確認**——這是這一大輪裡目前唯一還沒實機確認的部分。

`testDebugUnitTest`（154 個測試，含新增的 `GroupPagingTest` 5 個＋`movesCustomFieldBeforeTarget` 1 個）／`assembleDebug`／`lintDebug` 全數通過；`DiscouragedApi`（固定方向警告）跟拿掉 portrait 鎖時消失、現在因為鎖 landscape 又出現，是同一類預期中、已經記錄過取捨理由的警告，不是新問題。

**事後修正（同一輪，使用者回報第 4／第 8 張卡片被蓋住）**：上面「修正一個實機才會發現的排版 bug」那段的做法（把 `compact` 卡片的 padding/字體縮小＋幫 `SidePanel` 疊一層 `verticalScroll` 當保險）並沒有真正解決問題——縮小過的固定尺寸還是有可能比這台裝置實際可用的橫式高度大，捲動雖然加了，但使用者實際感受到的是「第 4／第 8 個參數被蓋住」，代表這個修法本質上還是「用猜的常數去對一個測不準的可用空間」，使用者也直接點出「要算一下螢幕大小做適合尺寸計算」。

改成真正用 `BoxWithConstraints` 量測 `SidePanel` 當下實際可用的高度（`maxHeight`），現場算出每張卡片該多高：`cardHeight = (可用高度 - 卡片間距總和) / 卡片數`，並設一個 `MIN_COMPACT_CARD_HEIGHT = 48.dp` 下限（`GlassStatCard` 的 compact 內容——圖示＋數值行＋標籤行——低於這個高度本身就會裁切，不只是排擠鄰居）；只有算出來的理想高度低於這個下限時，才退回捲動（此時卡片維持在下限高度、允許捲動看剩下的），不是像前一版那樣不管算出來多少一律先捲再說。拖曳排序原本用 `onGloballyPositioned` 量測第一張卡片實際渲染高度的做法也一併拿掉——現在卡片高度是算出來的已知值，直接拿來換算拖曳距離對應幾個位置，不需要再等佈局完成才量測。

實機部署驗證：這台裝置（CPH1877，橫式可用高度換算出來對這個版面剛好夠放 4 張卡片不用捲動）左右兩側各 4 張卡片全部完整顯示，先前被蓋住的第 4 張（引擎負載）與第 8 張（外部氣溫）都正常渲染、文字沒有裁切，沒有閃退。`testDebugUnitTest`／`assembleDebug`／`lintDebug` 全過。

**再修一次（同一輪，使用者回報「每個參數的字下半部被截斷了」）**：上面那個「算出精確高度」的版本雖然解決了第 4／第 8 張卡片被蓋住的問題，但引入了一個更細微的新問題——把每張卡片用 `Modifier.height(cardHeight)` 強制壓到算出來的精確高度，而 `MIN_COMPACT_CARD_HEIGHT = 48.dp` 這個下限本身是憑經驗公式估的（字級行高、padding 用概略比例換算），跟這台裝置實際渲染文字需要的真實高度有落差——只要算出來／夾在下限的高度比真實內容需要的矮一點點，`GlassStatCard` 自己的 `.clip(RoundedCornerShape(12.dp))` 就會把超出這個精確框框的文字下緣直接切掉，使用者看到的正是「每個參數的字下半部被截斷」，不是只有第 4／第 8 張，是全部卡片都受影響（因為所有卡片共用同一個算出來、統一偏低的高度）。

修法：徹底放棄「強制卡片為算出來的精確高度」這個做法，改成卡片一律用自己內容的自然大小（不設 `.height()`，拿掉整個 `Modifier.fillMaxSize()` 傳給 `GlassStatCard` 的做法），`SidePanel` 的 `Column` 一律套用 `verticalScroll`（不再用「算出來的高度是否小於可用空間」去決定要不要捲動）——量到的高度現在只拿來估算拖曳排序時「拖了多遠等於移動幾個位置」的粗略比例，不再拿去真的限制卡片的實際版面尺寸。這樣文字永遠用自己真正需要的空間渲染，不會被裁切；代價是原本「剛好不用捲動就看到 4 張」這個好處在某些裝置上可能會需要稍微往下滑一下才能看到第 4 張——但比起讓文字下緣被切掉，這是明顯更好的取捨。

實機驗證：`水溫`／`油量`／`電瓶電壓`／`行駛里程`／`瞬時油耗`／`平均油耗` 等文字都完整無截斷；額外用 `adb shell input swipe` 模擬往上滑的手勢，確認原本捲動到畫面外的第 4 張卡片（引擎負載）真的會捲進來、文字同樣完整——證實這次的兩個修正（不截斷文字＋捲動可以看到全部卡片）同時成立，不是顧此失彼。`testDebugUnitTest`／`assembleDebug`／`lintDebug` 全過，沒有閃退。

### 2026-09-15（第三十輪：追加一輪 iOS commit——8 槽 FIFO 上限、確認死掉的胎壓欄位、給予退避冷卻、語音改成超速警示）

使用者要求「Revise code based on new GitHub push」。拉取新 commit（`d5e8b6f`，"feat(ios): unpin dead TPMS fields, home-screen card delete badge, speed alerts"），逐一核對並搬過來：

1. **自訂欄位上限 8 個、FIFO 淘汰**——`toggleCustomField()` 原本新增欄位時無上限（`current + field`），而畫面只取前 8 個顯示（`pinnedFields.take(8)`）——這代表已經選滿 8 個之後再勾選第 9 個，使用者會看到「勾選了但畫面完全沒反應」，因為新欄位被加到清單尾端、永遠超出顯示範圍。改成新增時如果超過 8 個就把最舊的（`drop(1)`）踢掉，跟 iOS 的 `RingLegendFieldPickerView`（2 槽 FIFO）用同一套邏輯，只是槽位數不同。
2. **排除確認已死的 4 個輪胎壓力／溫度欄位（8 個實際欄位：前左/右、後左/右各壓力＋溫度）**——iOS 團隊用「查詢胎壓原始值」診斷探測（第二十七輪就是為了這個加的）在真車上實測，確認這 8 個欄位每次查詢都是 NO DATA，這台車（或至少這個底盤世代）沒有裝那個胎壓偵測選配模組，不是位址/公式寫錯。這 8 個欄位本來就已經定義在 `shared/vehicle-profiles/citroen.json`（Android／iOS 共用同一份，都會被輪詢＋出現在挑選器裡）。新增 `UNAVAILABLE_TIRE_FIELDS` 常數：
   - 從 `relevantFieldsFor()`（自訂區塊／中央圖例的可選清單）裡排除。
   - 從 `restartBrandPolling()` 實際輪詢的 PID 清單裡排除（沒必要浪費匯流排頻寬問一個確定不會回應的東西）。
   - **不是把 JSON 定義刪掉**——留著原始定義，只在程式碼層排除，因為換一台真的有裝這個選配的 Berlingo/Citroën 可能就會有回應。
   - ViewModel 建構時清掉使用者可能已經勾選過的殘留（`cleanedCustomFields`／`cleanedRingLegendFields`，讀出來過濾一次，如果有變動就寫回去持久化），避免這幾張永遠顯示「--」的卡片卡在自訂區塊裡拿不掉。
3. **`speedKPH`／`rpm`／`mafGramsPerSec` 也從 `relevantFieldsFor()` 排除**——這三個欄位是快速迴圈直接寫進 `vehicleData`／行車電腦狀態，從來不會進到 `standardReadings`/`extraReadings`（也就是自訂區塊／群組頁面實際讀取畫面數字的來源），選進自訂區塊或中央圖例只會永遠顯示「--」——這是先前完全沒注意到、iOS 這輪才發現並修的一個真實的小 bug，Android 一直都有同樣的問題（畢竟本來就允許使用者選 `speedKPH`/`rpm` 進自訂區塊）。
4. **PID 給棄邏輯改成「冷卻後重試」，不是永久放棄**——原本連續失敗 5 次就整個連線期間永遠不再問這個 PID；改成失敗 5 次後冷卻 `GIVE_UP_COOLDOWN_TURNS = 25` 輪，冷卻期滿後重新給一次機會（如果那次也失敗，要再連續失敗 5 次才會重新觸發冷卻，不是立刻又進入永久放棄狀態）。理由：像檔位這種平常都會回應、只是偶爾在怠速/匯流排短暫安靜時連續問不到的 PID，永久放棄代表整趟車剩下的時間都卡在「--」，即使 ECU 後來又願意回應了也一樣；真的不支援的 PID（例如上面那組胎壓欄位，現在已經直接從輪詢清單排除，不會進到這個機制）大不了偶爾浪費一次重試，換到「假死」永遠不恢復的風險小很多。
5. **拿掉變檔語音播報，改成超速門檻警示**——iOS 這輪直接把 `gearRaw` 的語音播報整個拿掉（畫面上的 P/R/N 格式化顯示`formatGearDisplay`不受影響，只有語音的部分被移除），换成車速超過 110/120/130 km/h 時各念一次（`announceSpeedIfNeeded`：車速低於最低的門檻(110)時清空「已播報過的門檻」記錄，代表下一次再衝上 110 會重新播報一次；只要沒掉到 110 以下，130 降到 115 途中不會重播 110/120，因為它們早就播過了；一旦真的掉到 110 以下又衝上去，才算一次新的超速）。跟電瓶電壓播報用同一顆 `SpeechAnnouncer`，一樣沒有開關、每次(重新)連線都重置門檻記錄。
6. **環形錶峰值標記箭頭方向修正**——原本箭頭指向「數值成長的方向」（沿弧線前進的切線方向），iOS 這輪根據實際使用回饋改成指向反方向（沿弧線「來時路」，backward），因為「指向前方」在直覺上容易誤讀成「數值正在往那邊走」而不是「峰值在這裡」；數字標籤位置也從「往前方再拉遠一點」改成「就貼在箭頭底部旁邊」。Android 的 `DrivingRingGauge.kt` 逐一對照修正了向量方向與標籤定位運算式。

**決定暫緩、不在這輪做的部分**：iOS 新增了「長按側邊卡片進入編輯模式、每張卡片右上角冒出一個 × 刪除徽章，點一下就能直接移除、不用開完整的欄位挑選器」（跟 iOS 系統主畫面「搖晃模式」同樣的互動比喻）。Android 上一輪剛做好「長按拖曳排序」（`detectDragGesturesAfterLongPress`），跟這個新的「長按進入編輯模式」在手勢觸發時機上高度重疊（都是同一個長按動作），要在 Compose 上同時穩定地做「長按可能觸發拖曳，也可能觸發編輯模式，鬆手後還要跟雙擊開啟挑選器互不干擾」這三種手勢共存，需要比 SwiftUI 的 `simultaneousGesture` 更小心設計的手勢仲裁邏輯，這輪先不倉促動手，只記錄下來——目前使用者仍然可以透過雙擊卡片開啟完整挑選器來取消勾選，只是少了「一鍵刪除」這個捷徑。

全部改完，連帶修正／新增了對應的單元測試：`formatsAndAnnouncesParkAndReverseGearsSpecially` 拿掉語音斷言、改名 `formatsParkAndReverseGearsSpecially`（畫面格式化本身沒變，只是語音的部分已經不存在了）；`announcesGearChangesButNotTheFirstBaselineReading` 整個換成 `announcesSpeedThresholdCrossingsOncePerExcursion`（鎖住新的超速門檻播報行為，含「降到門檻以下才重新武裝」這個細節）；`stopsPollingStandardExtraPidPermanentlyAfterRepeatedFailures` 改名 `backsOffAfterRepeatedFailuresThenRetriesOnceCooldownElapses`（原本斷言「永遠不會再有新的請求」已經不成立，改成驗證「冷卻期間內沒有新請求、冷卻期滿了之後真的會再試一次」）；新增 `capsCustomFieldsAtEightWithFifoEviction`／`excludesFastLoopAndDeadTireFieldsFromKnownFields`／`cleansStoredDeadTireFieldsOnLoad` 三個新測試，並新增 `FakeRingLegendFieldsStore` 測試替身。`testDebugUnitTest`（157 個測試）／`assembleDebug`／`lintDebug` 全過。手機這輪沒有連線，還沒機會實機驗證這批修正（尤其是超速語音警示，需要真的加速超過門檻才能聽到）。

### 2026-09-14（第二十八輪：使用者要求把橫式支援正式排入範圍——解除旋轉鎖）

使用者明確要求「Add it into scope and develop」。重新檢查根因後發現一個好消息：**上一輪為了 PiP 功能已經在 `AndroidManifest.xml` 加上 `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|uiMode"`**——這個宣告本來是為了讓進出 PiP 視窗時的尺寸變化不要重建 Activity，但它同時也涵蓋了 `orientation` 這個 config-change 類別。原本鎖 portrait 的理由（旋轉觸發 Activity 重建 → 重跑 `MainActivity`裡的 `LaunchedEffect(Unit) { requestScanOrStart() }` → 對已連線的裝置又呼叫一次 `startScan()` → BLE 連線狀態被弄壞）的根本原因是「Activity 被重建」，而不是「旋轉」這件事本身——只要 Activity 不重建，`LaunchedEffect(Unit)` 就不會重新執行，舊 bug 自然不會發生。既然 `configChanges` 已經宣告好「orientation 變化由 Activity 自己處理、不要重建」，這個根因其實已經被上一輪的 PiP 工作意外解決了，這輪只需要把 `android:screenOrientation="portrait"` 這個鎖拿掉即可。

**決定不做的部分**：沒有跟著做 iOS 那套全新的 landscape-only 環形錶版面（`DrivingDynamicsDashboardView`／`GroupPaging`／`RingLegendFieldPickerView`）——那是一個獨立、規模大很多、需要視覺設計決策的專案。這輪的範圍界定為「讓旋轉安全、不會閃退／弄壞連線」，畫面內容維持現有的指針錶＋格狀清單，在橫式時就讓它在既有的可捲動 `LazyColumn`／`Column` 裡自然重排（螢幕變寬變矮，兩個指針錶可能需要往下捲動才看得到全部，不是為橫式特別優化的版面，但不會壞掉）。已經在 manifest 註解裡把這個範圍界定寫清楚，避免之後誤以為「有做橫式」等於「有做 iOS 那套環形錶」。

修改：拿掉 `<activity>` 的 `android:screenOrientation="portrait"` 屬性，`configChanges` 維持不變（已經涵蓋 orientation）。`lintDebug` 確認 `LockedOrientationActivity`／`DiscouragedApi` 這兩個先前因為鎖 portrait 產生的警告都消失了（警告數從 14 降到 12）。`testDebugUnitTest`／`assembleDebug`／`lintDebug` 全部通過。

手機這輪沒有連線，還沒機會實機測試「真的旋轉手機、確認不閃退、BLE 連線在旋轉後還活著」——這是下一次手機連上時最優先要驗證的項目，因為這正是原本那個 bug 唯一沒辦法用單元測試複現的部分（需要真的觸發一次 config change）。

**實機驗證（同一輪，手機重新連上後）**：部署後請使用者實際把手機轉成橫式一次（`adb` 在這台 ColorOS 機器上被鎖死了 `WRITE_SETTINGS`／`wm user-rotation` 都無法用來模擬旋轉，只能請使用者真的轉）。旋轉前後用 `dumpsys activity activities` 比對 `ActivityRecord` 的識別碼——**旋轉前後完全是同一個 `ActivityRecord{5c4550b ...}`**，證明 Activity 真的沒有被重建，`configChanges` 生效，舊 bug 的根因（重建→重跑 `LaunchedEffect`→重新 `startScan()`）不會發生。`dumpsys window displays` 確認畫面真的變成 `real 2340 x 1080`（橫式），截圖確認畫面內容正確重排（標題列、掃描狀態、雙儀表都正常渲染，儀表因為沒有做橫式專用版面而變得比較大，需要往下捲動才看得到「自訂」區塊，符合這輪範圍界定的預期），旋轉前後 `logcat` 都沒有任何 error/exception/crash。這一輪的橫式支援已經完整驗證，包含最關鍵、單元測試測不到的「真的觸發一次 config change」這一步。
