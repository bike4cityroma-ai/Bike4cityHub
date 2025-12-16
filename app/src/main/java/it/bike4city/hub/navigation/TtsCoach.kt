package it.bike4city.hub.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsCoach(ctx: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ready = false

    var muted: Boolean = false

    override fun onInit(status: Int) {
        ready = (status == TextToSpeech.SUCCESS)
        if (ready) {
            tts.language = Locale.ITALY
            // evita che si accavallino frasi
            tts.setSpeechRate(1.05f)
        }
    }

    fun speak(text: String) {
        if (!ready || muted) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
