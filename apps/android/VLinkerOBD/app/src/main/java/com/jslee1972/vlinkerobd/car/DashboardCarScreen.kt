package com.jslee1972.vlinkerobd.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jslee1972.vlinkerobd.VLinkerObdApplication
import com.jslee1972.vlinkerobd.ui.DashboardUiState
import kotlinx.coroutines.launch

/**
 * A minimal, template-only car-screen view of the same live [DashboardUiState] the phone shows.
 * The Car App Library only allows fixed host-rendered templates (no custom Canvas/Compose
 * drawing) unless an app is in the Navigation category — which this app doesn't qualify for — so
 * this is plain text rows, not the gauge dashboard. Kept intentionally small (4 rows): PaneTemplate
 * hosts are free to reject a template that's too dense for the car's screen/driver-distraction
 * rules.
 */
class DashboardCarScreen(carContext: CarContext) : Screen(carContext) {

    private val viewModel = (carContext.applicationContext as VLinkerObdApplication).dashboardViewModel
    private var latestState: DashboardUiState = viewModel.uiState.value

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    viewModel.uiState.collect { state ->
                        latestState = state
                        invalidate()
                    }
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val state = latestState
        val connectionText = state.connectedDeviceName?.let { "${state.connectionLabel}：$it" } ?: state.connectionLabel

        val pane = Pane.Builder().apply {
            addRow(Row.Builder().setTitle("連線狀態").addText(connectionText).build())
            state.detectedBrand?.let { addRow(Row.Builder().setTitle("車款").addText(it).build()) }
            addRow(Row.Builder().setTitle("車速").addText(state.vehicleData.speedKph?.let { "$it km/h" } ?: "--").build())
            addRow(Row.Builder().setTitle("轉速").addText(state.vehicleData.rpm?.let { "$it rpm" } ?: "--").build())
        }.build()

        return PaneTemplate.Builder(pane)
            .setHeaderAction(Action.APP_ICON)
            .setTitle("行車通")
            .build()
    }
}
