package com.padelsync.kit

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks the score through the phone's or watch's text-to-speech voice.
 *
 * The voice engine is started the first time something needs saying, so a
 * device with announcements switched off never loads it. A new announcement
 * replaces one still being spoken: the score that matters is the latest.
 *
 * Call every method from the main thread.
 *
 * @param onUnavailable called once, on the main thread, if the device has no
 * usable voice.
 */
internal class Announcer(
    context: Context,
    private val handler: Handler,
    private val onUnavailable: () -> Unit,
) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Spoken like a sat-nav prompt: on the media volume, briefly lowering any music.
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private var engine: TextToSpeech? = null
    private var ready = false
    private var failed = false

    /** The latest thing asked for while the engine was still starting. */
    private var waiting: String? = null
    private var utterance = 0

    /** Whether the device turned out to have no usable voice. */
    val unavailable: Boolean
        get() = failed

    /** Speaks [phrases] as one announcement. Does nothing for an empty list. */
    fun say(phrases: List<String>) {
        if (phrases.isEmpty() || failed) return
        val text = phrases.joinToString(" ")
        val current = engine
        when {
            current == null -> {
                waiting = text
                start()
            }
            !ready -> waiting = text
            else -> speak(current, text)
        }
    }

    /** Stops talking at once. */
    fun silence() {
        waiting = null
        if (ready) engine?.stop()
        audio.abandonAudioFocusRequest(focusRequest)
    }

    private fun start() {
        try {
            engine = TextToSpeech(app) { status ->
                // The engine reports from its own thread on some devices.
                handler.post { onStarted(status) }
            }
        } catch (e: RuntimeException) {
            fail()
        }
    }

    private fun onStarted(status: Int) {
        val current = engine
        if (status != TextToSpeech.SUCCESS || current == null) {
            fail()
            return
        }
        try {
            current.setAudioAttributes(attributes)
            // The wording is English; fall back to whatever the device has.
            val result = current.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                current.setLanguage(Locale.getDefault())
            }
            current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = release(utteranceId)

                // Deprecated in the platform, but still the callback older engines use.
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) = release(utteranceId)

                override fun onError(utteranceId: String?, errorCode: Int) = release(utteranceId)

                override fun onStop(utteranceId: String?, interrupted: Boolean) = release(utteranceId)
            })
        } catch (e: RuntimeException) {
            fail()
            return
        }
        ready = true
        waiting?.let { speak(current, it) }
        waiting = null
    }

    private fun speak(current: TextToSpeech, text: String) {
        audio.requestAudioFocus(focusRequest)
        utterance++
        val result = try {
            current.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utterance.toString())
        } catch (e: RuntimeException) {
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) audio.abandonAudioFocusRequest(focusRequest)
    }

    /** Gives the music its volume back once the latest announcement has finished. */
    private fun release(utteranceId: String?) {
        handler.post {
            if (utteranceId == utterance.toString()) audio.abandonAudioFocusRequest(focusRequest)
        }
    }

    private fun fail() {
        failed = true
        ready = false
        waiting = null
        try {
            engine?.shutdown()
        } catch (e: RuntimeException) {
            // Nothing more to release.
        }
        engine = null
        onUnavailable()
    }
}
