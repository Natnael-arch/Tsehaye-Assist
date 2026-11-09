package com.example.voicelauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

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

    fun play(announcement: Announcement) {
        val resId = context.resources.getIdentifier(announcement.clip, "raw", context.packageName)
        if (resId == 0) {
            fail(announcement, "clip_missing")
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

    fun release() {
        val mp = player ?: return
        player = null
        try {
            mp.stop()
        } catch (e: Exception) {
            // not prepared yet
        }
        mp.release()
        audioManager.abandonAudioFocusRequest(focusRequest)
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
