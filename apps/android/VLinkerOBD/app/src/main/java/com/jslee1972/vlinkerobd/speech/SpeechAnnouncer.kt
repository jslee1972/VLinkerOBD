package com.jslee1972.vlinkerobd.speech

/** Speaks short, glanceable-while-driving announcements. Fakeable so [com.jslee1972.vlinkerobd.ui.DashboardViewModel] stays unit-testable. */
interface SpeechAnnouncer {
    fun speak(text: String)
}

object NoOpSpeechAnnouncer : SpeechAnnouncer {
    override fun speak(text: String) = Unit
}
