package com.jslee1972.vlinkerobd.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point Android Auto binds to. [HostValidator.ALLOW_ALL_HOSTS_VALIDATOR] is only
 * appropriate for a sideloaded/personal-use app like this one — a version distributed on Play
 * would need a real allowlist validator instead.
 */
class VLinkerCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = VLinkerCarSession()
}
