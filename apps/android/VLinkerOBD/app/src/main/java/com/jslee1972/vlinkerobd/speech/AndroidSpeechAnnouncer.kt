package com.jslee1972.vlinkerobd.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Wraps Android's [TextToSpeech] for zh-TW announcements (falls back to zh-CN if the TW voice
 * data isn't installed). Uses [AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE] so the
 * announcement ducks any other audio (music, podcasts) the same way turn-by-turn nav apps
 * interrupt playback, instead of getting buried under it or silenced by ringer mode.
 */
class AndroidSpeechAnnouncer(context: Context) : SpeechAnnouncer {

    @Volatile
    private var ready = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.TAIWAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            }
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            ready = true
        }
    }

    override fun speak(text: String) {
        if (!ready) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }
}
