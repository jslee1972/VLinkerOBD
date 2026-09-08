package com.jslee1972.vlinkerobd.ui

/**
 * Plain-language explanations of what each parameter measures and why it might be worth pinning
 * into the dashboard's custom section — shown behind the "i" info icon in the field picker.
 * Standard (universal-obd2.json) fields are described from the official SAE J1979 spec this
 * project's formulas were themselves verified against; brand-specific fields are described from
 * what's already documented in their source profile's own notes. A field with no entry here
 * falls back to a generic "尚無詳細說明" message rather than a fabricated explanation, same
 * honesty rule [PidDisplayNames] and [com.jslee1972.vlinkerobd.obd.DtcDescriptions] use.
 */
object ParameterDescriptions {
    private val zh: Map<String, String> = mapOf(
        // trip computer (derived — see DashboardViewModel.updateTripComputer)
        "instantFuelConsumption" to "用進氣流量（MAF）換算出的瞬時油耗估算值，單位是每公升可以跑幾公里。這不是車輛直接回報的數字，是這個 App 自己算出來的估計值，僅供參考。",
        "averageFuelConsumption" to "本次連線以來的累計油耗估算值（公里/公升），從瞬時油耗持續累加距離與耗油量算出來的，同樣是估算值，不是原廠儀表板的油耗數字。",
        "acceleration" to "由車速變化率計算出的加速度，正值代表加速、負值代表減速。想觀察開車習慣是否平順（急加速/急煞車）時適合勾選。",
        "tripDistance" to "本次連線後累計行駛的距離估算值，斷線或重新連線會歸零，不是車輛本身的累積里程表。",
        "tripDuration" to "本次連線後經過的時間，跟引擎是否發動、是否行駛無關，純粹是連線後經過的分鐘數。",
        // universal-obd2.json
        "coolantTempC" to "引擎冷卻液（水箱水）的溫度。正常運轉時通常落在 85–105°C 之間；長時間偏低代表恆溫器可能故障，過高則是過熱警訊，是判斷引擎健康狀況最基本的一個數值。",
        "engineLoadPercent" to "引擎目前的計算負荷百分比，反映引擎相對於最大進氣能力的負擔程度，油門全開、爬坡、載重時會升高。可以用來觀察引擎是否吃力。",
        "throttlePercent" to "節氣門實際開度百分比，反映油門踏板透過節氣門系統實際控制進氣的程度，跟轉速/車速一起看可以了解駕駛操作。",
        "controlModuleVoltage" to "引擎控制模組量到的車用電瓶（通常是 12V 系統）電壓。發動機運轉時應該在 13.5–14.5V 左右（代表發電機正常充電），熄火時偏低則是電瓶健康度的指標。",
        "intakeAirTempC" to "進氣岐管內的空氣溫度，會受室外氣溫、引擎室散熱影響，用來計算進氣密度以校正噴油量。",
        "mafGramsPerSec" to "質量空氣流量感測器（MAF）量到的每秒進氣質量，是估算瞬時油耗、判斷引擎進氣是否正常最關鍵的原始數據之一。",
        "intakeManifoldPressureKPA" to "進氣岐管內的絕對壓力（歧管真空度的另一種表示方式），怠速時數值較低、油門全開時接近大氣壓力，渦輪車全油門時可能超過大氣壓力。",
        "fuelPressureKPA" to "燃油供給系統的壓力（部分車輛才有回報這個值），過低可能代表油泵或油壓穩壓器有問題，影響噴油量精準度。",
        "barometricPressureKPA" to "目前所在地的大氣壓力，海拔越高數值越低，ECU 會用這個值校正噴油與點火，跟天氣預報的氣壓意義不完全相同（沒有換算到海平面）。",
        "fuelLevelPercent" to "油箱油量百分比，來源是油箱浮筒感測器換算的訊號，跟儀表板油量表通常是同一組數據。",
        "ambientAirTempC" to "車外環境溫度感測器讀值，跟車內顯示的外部氣溫通常是同一顆感測器。",
        "engineOilTempC" to "機油溫度（不是所有車都有回報這個 PID），比水溫更能反映引擎內部實際運轉溫度，賽道/激烈操駕時比較會關注這個數值。",
        "runtimeSinceStartSec" to "這次發動引擎後已經運轉的秒數，重新熄火發動會歸零。",
        "shortTermFuelTrimBank1Percent" to "ECU 針對汽缸列 1（Bank 1）即時微調噴油量的百分比，正值代表正在加油、負值代表正在減油，用來讓混合氣維持在理論空燃比附近。長期偏離 0 太多可能代表進氣或噴油系統有洩漏/積碳等問題。",
        "longTermFuelTrimBank1Percent" to "Bank 1 的長期燃油修正值，是短期修正經過一段時間學習後的平均趨勢，比瞬時的短期修正更能反映系統性的問題（如引擎老化、感測器飄移）。",
        "shortTermFuelTrimBank2Percent" to "跟 Bank 1 短期燃油修正意義相同，只是對象是另一列汽缸（V型或水平對臥引擎才會有兩個 Bank）。",
        "longTermFuelTrimBank2Percent" to "跟 Bank 1 長期燃油修正意義相同，對象是 Bank 2 汽缸列。",
        "timingAdvanceDegrees" to "目前的點火提前角（相對於一號缸上死點的角度），引擎爆震、爬坡重載時 ECU 通常會自動略為延後點火時機來保護引擎。",
        "distanceWithMilOnKM" to "故障燈（MIL）亮起後，車輛已經行駛的距離。故障排除後這個數字通常會歸零。",
        "chargeAirCoolerTempC" to "中間冷卻器（Intercooler）出口的進氣溫度，渦輪/機械增壓車才會有意義，數值越接近外氣溫代表中冷器散熱效果越好。",
        "lambdaBank1Sensor1" to "氧感測器量到的實際空燃比相對於理論空燃比的比值（1.0 代表剛好在理論值），配合短期/長期燃油修正可以判斷混合氣偏濃或偏稀。",
        "boostPressureCommandedKPA" to "渦輪增壓系統的目標（指令）增壓值，來自 ECU 的控制邏輯。",
        "boostPressureActualKPA" to "渦輪增壓系統目前實際量到的增壓值，跟目標值差太多可能代表洩壓閥、渦輪控制機構有問題。",
        "exhaustPressureBank1KPA" to "Bank 1 排氣系統的壓力，柴油車常用來監控 DPF（排氣微粒濾清器）是否阻塞。",
        "dpfInletPressureKPA" to "柴油車 DPF（排氣微粒濾清器）進氣端壓力，跟出氣端壓力的壓差是判斷 DPF 阻塞程度的重要依據。",
        "dpfOutletPressureKPA" to "柴油車 DPF 出氣端壓力，配合進氣端壓力計算壓差。",
        "fuelRailPressureCommandedKPA" to "共軌式噴射系統的目標燃油軌壓，柴油車跟缸內直噴汽油車才會有意義。",
        "fuelRailPressureActualKPA" to "燃油軌目前實際壓力，跟目標值差距過大可能代表高壓油泵或壓力調節閥有問題。",
        "engineFuelRateLPH" to "ECU 自己算出的瞬時油耗率（公升/小時），如果車輛有回報這個 PID，通常比本 App 自己用 MAF 估算的瞬時油耗更準確，因為是原廠用噴油嘴實際噴油時間直接算出來的。",
        "exhaustGasTempBank1Sensor1C" to "Bank 1 排氣溫度感測器 1 的讀值（通常靠近觸媒轉化器前端），用來保護觸媒與監控燃燒狀況，柴油車也用來監控 DPF 再生溫度。",
        "exhaustGasTempBank1Sensor2C" to "Bank 1 排氣溫度感測器 2 的讀值，位置通常在感測器 1 之後（例如 DPF 前後）。",
        "exhaustGasTempBank1Sensor3C" to "Bank 1 排氣溫度感測器 3 的讀值，位置又更後段，不是所有車都有到第三顆感測器。",
        "noxBank1Sensor1PPM" to "Bank 1 氮氧化物（NOx）感測器讀值，柴油車跟部分符合更嚴格排放法規的汽油車才會有，用來監控排放是否超標。",
        "absoluteLoadPercent" to "跟一般的引擎負荷百分比類似，但是用進氣量對照最大理論進氣量（不受海拔/進氣溫度影響）算出來的『絕對』負荷值，不同車輛間比較會更一致。",
        "commandedEquivalenceRatio" to "ECU 下達的目標當量比（實際空燃比相對理論空燃比的倒數關係），主要用在稀薄燃燒或雙燃料系統的車輛。",
        "relativeThrottlePercent" to "相對節氣門開度，是以節氣門「最小可控位置」為 0% 起算，跟 throttlePercent（絕對開度）算法基準點不同。",
        "ethanolFuelPercent" to "目前燃油中乙醇的比例，適用可彈性燃料（Flex-Fuel，E85 等）車輛，會影響 ECU 的噴油與點火校正。",
        "fuelRailPressureAbsoluteKPA" to "燃油軌絕對壓力（含大氣壓力基準），跟 fuelRailPressureActualKPA/CommandedKPA 是用不同基準表示同一類數據，缸內直噴車輛常見。",
        "relativeAcceleratorPedalPercent" to "駕駛實際踩踏油門踏板的相對位置百分比，是「電子油門」系統中最接近『駕駛意圖』的原始訊號，跟節氣門開度（引擎實際回應）分開來看可以判斷 ECU 是否介入調整（例如循跡防滑、定速巡航）。",
        "driverDemandTorquePercent" to "ECU 根據油門踏板位置換算出的『駕駛需求扭矩』百分比，是現代電子節氣門引擎控制的核心概念之一。",
        "actualEngineTorquePercent" to "引擎目前實際輸出扭矩相對於最大扭矩的百分比，可以跟駕駛需求扭矩比較，判斷 ECU 有沒有因為保護引擎、循跡系統介入等原因限制輸出。",
        "engineReferenceTorqueNM" to "引擎在額定轉速下的參考（額定）最大扭矩值，是換算 actualEngineTorquePercent/driverDemandTorquePercent 百分比用的基準值。",
        // citroen.json (PSA) — from nico1080/OBD-LCD-display-for-PSA
        "turboPressureBar" to "PSA 集團車輛渦輪實際增壓壓力（私有 DID，非標準 PID）。",
        "turboPressureSetpointBar" to "PSA 車輛渦輪目標增壓壓力，跟實際值比較可以看出渦輪控制系統是否正常。",
        "turboTempC" to "PSA 車輛渦輪本體溫度讀值。",
        "mapPressureBar" to "PSA 車輛的進氣岐管壓力（私有 DID，跟標準的 intakeManifoldPressureKPA 是不同感測器/定址方式，渦輪車的感測點位置可能不同）。",
        "mapTempC" to "PSA 車輛進氣岐管溫度（私有 DID）。",
        "oilPressureBar" to "PSA 車輛機油壓力讀值，低於正常範圍是引擎潤滑系統的重要警訊。",
        "engineTorqueNm" to "PSA 車輛引擎目前輸出扭矩（私有 DID，單位直接是牛頓米）。",
        "gearRaw" to "PSA 車輛目前檔位數字（手排 1–6 檔）。原始資料來源沒有 P/R/N/D 的對照表，只有单純的排檔數字，超出 1–6 範圍的數值目前沒有可信資料確認代表什麼。",
        "tireFrontLeftPressureBar" to "PSA 車輛左前輪胎壓（私有 DID，來自胎壓偵測系統）。",
        "tireFrontRightPressureBar" to "PSA 車輛右前輪胎壓。",
        "tireRearLeftPressureBar" to "PSA 車輛左後輪胎壓。",
        "tireRearRightPressureBar" to "PSA 車輛右後輪胎壓。",
        "tireFrontLeftTempC" to "PSA 車輛左前輪胎溫，來自胎壓偵測系統內建的溫度感測器。",
        "tireFrontRightTempC" to "PSA 車輛右前輪胎溫。",
        "tireRearLeftTempC" to "PSA 車輛左後輪胎溫。",
        "tireRearRightTempC" to "PSA 車輛右後輪胎溫。",
        "auxBatteryVoltageV" to "PSA 車輛輔助（12V）電瓶電壓，跟標準的 controlModuleVoltage 是不同感測點/定址方式。",
        "auxBatteryTempC" to "PSA 車輛輔助電瓶溫度，電瓶效能會隨溫度變化，過冷或過熱都會影響啟動性能。",
        "fuelInstantConsumptionLh" to "PSA 車輛原廠回報的瞬時油耗（公升/小時），跟本 App 自己用 MAF 估算的瞬時油耗是不同來源，如果這個欄位有資料通常比估算值更準確。",
        "fuelTripConsumptionL" to "PSA 車輛原廠回報的本趟耗油量（公升），是原廠行車電腦的資料，不是本 App 自己算的。",
        "remainingRangeKmPSA" to "PSA 車輛原廠儀表板顯示的剩餘可行駛里程估計值。",
        // citroen-ev.json (Citroën/Peugeot EMP2 純電平台 — 取自 OVMS vehicle_fiatedoblo)
        "evBatteryVoltageV" to "純電 PSA 車輛（如 ë-Berlingo）的高壓動力電池組電壓，燃油版車輛不會有這個資料。",
        "evBatteryCurrentA" to "高壓動力電池組電流，正負號代表充電或放電方向，只適用純電車款。",
        "evBatteryMinCellVoltageV" to "電池組裡電壓最低的單一電芯電壓，跟最高電芯電壓的差距是判斷電池組是否需要平衡的重要指標。",
        "evBatteryMaxCellVoltageV" to "電池組裡電壓最高的單一電芯電壓。",
        "evBatteryAvailableKWh" to "目前電池組可用電量（度數），只適用純電車款。",
        "evBatterySohPercent" to "電池健康度（State of Health）百分比，反映電池組相對於全新狀態的容量衰退程度，只適用純電車款。",
        "evAmbientTempC" to "純電車款車輛控制單元（VCU）量到的外部氣溫，跟標準的 ambientAirTempC 來源不同。",
        "evBatteryTempC" to "高壓動力電池組溫度，電池溫度過高或過低都會影響充放電效率與壽命，只適用純電車款。",
        "evDcDcConverterTempC" to "高壓轉低壓 DC-DC 轉換器溫度，只適用純電/油電車款。",
        "evOnboardChargerTempC" to "車載充電器（On-Board Charger）溫度，充電時才會有意義的讀數，只適用純電車款。",
        // mazda.json
        "tire1PressurePSI" to "Mazda 車輛胎壓感測器 1 讀值（實際對應哪一輪視車型而定，原始資料沒有明確標示位置）。",
        "tire2PressurePSI" to "Mazda 車輛胎壓感測器 2 讀值。",
        "tire3PressurePSI" to "Mazda 車輛胎壓感測器 3 讀值。",
        "tire4PressurePSI" to "Mazda 車輛胎壓感測器 4 讀值。",
        // ford.json
        "odometerKM" to "Ford 車輛原廠累積里程表讀值（私有 DID），跟車輛儀表板顯示的總里程一致。",
        "tirePressureWarning" to "Ford 車輛胎壓警示燈狀態（開/關），這個 App 目前用數字 0/1 顯示，還沒有轉換成文字說明。",
        "tireFrontLeftPSI" to "Ford 車輛左前輪胎壓。",
        "tireFrontRightPSI" to "Ford 車輛右前輪胎壓。",
        "tireRearRightPSI" to "Ford 車輛右後輪胎壓。",
        "tireRearLeftPSI" to "Ford 車輛左後輪胎壓。",
        // honda.json
        "stateOfChargePercent" to "Honda 油電車（Hybrid）高壓電池電量百分比，只適用有油電系統的車款。",
        "batteryVoltage" to "Honda 油電車高壓電池組電壓。",
        "batteryCurrent" to "Honda 油電車高壓電池組電流，正負號代表充電或放電。",
        "engineCoolantTemp1C" to "Honda 油電車引擎冷卻液溫度感測器 1（部分油電車系統有兩組獨立的冷卻迴路）。",
        "engineCoolantTemp2C" to "Honda 油電車引擎冷卻液溫度感測器 2。",
        "generatorDutyPercent" to "Honda 油電車發電機（Generator/MG1）運作占空比，反映引擎驅動發電機發電的工作強度。",
    )

    /** Traditional-Chinese explanation for [field], or a generic fallback if not yet documented. */
    fun description(field: String): String = zh[field] ?: "尚無詳細說明。"
}
