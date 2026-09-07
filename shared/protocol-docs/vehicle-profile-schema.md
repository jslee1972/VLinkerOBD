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
