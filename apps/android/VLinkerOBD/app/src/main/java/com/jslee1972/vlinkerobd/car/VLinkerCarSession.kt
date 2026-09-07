package com.jslee1972.vlinkerobd.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class VLinkerCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = DashboardCarScreen(carContext)
}
