package com.example.voicelauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class AnnouncementPlayer(
    private val context: Context,
    private val stopModelAudio: () -> Unit,
    private val onFailed: (Announcement, String) -> Unit
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            audioManager.abandonAudioFocusRequest(focusRequest)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            audioManager.abandonAudioFocusRequest(focusRequest)
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS fallback initialization failed", e)
        }
    }

    fun play(announcement: Announcement) {
        val resId = context.resources.getIdentifier(announcement.clip, "raw", context.packageName)
        if (resId == 0) {
            fail(announcement, "clip_missing")
            speakFallback(announcement)
            return
        }
        release()
        // PcmPlayer's AudioTrack never requests focus, so the framework will not duck it for us.
        stopModelAudio()
        audioManager.requestAudioFocus(focusRequest)
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(attributes)
            mp.setDataSource(context, Uri.parse("android.resource://${context.packageName}/$resId"))
            mp.setOnPreparedListener {
                Log.i(TAG, "PLAY clip=${announcement.clip}")
                it.start()
            }
            mp.setOnCompletionListener {
                Log.i(TAG, "END clip=${announcement.clip}")
                release()
            }
            mp.setOnErrorListener { _, what, extra ->
                fail(announcement, "mediaplayer_error_$what/$extra")
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Could not play ${announcement.clip}", e)
            fail(announcement, "exception_${e.javaClass.simpleName}")
        }
    }

    private fun speakFallback(announcement: Announcement) {
        if (!ttsReady || tts == null) return
        try {
            stopModelAudio()
            audioManager.requestAudioFocus(focusRequest)
            tts?.speak(announcement.english, TextToSpeech.QUEUE_FLUSH, null, announcement.clip)
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed", e)
        }
    }

    fun release() {
        val mp = player
        player = null
        if (mp != null) {
            try {
                mp.stop()
            } catch (e: Exception) {
                // not prepared yet
            }
            mp.release()
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    fun shutdown() {
        release()
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun fail(announcement: Announcement, why: String) {
        release()
        Log.e(TAG, "ANNOUNCEMENT FAILED clip=${announcement.clip} why=$why script=${announcement.amharic}")
        onFailed(announcement, why)
    }

    private companion object {
        const val TAG = "AnnouncementPlayer"
    }
}
