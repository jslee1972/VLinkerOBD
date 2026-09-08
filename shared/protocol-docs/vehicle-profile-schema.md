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
