package com.example.voicelauncher

import android.accessibilityservice.AccessibilityService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

class AccessibilityAudioService : AccessibilityService() {

    companion object {
        var instance: AccessibilityAudioService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Required by AccessibilityService.
     * Must be implemented, even if empty, to keep the service alive in the background
     * and continue receiving events so it is considered active by the OS.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't necessarily need to process UI events for our audio use case,
        // but this method must exist to maintain the service lifecycle.
    }

    override fun onInterrupt() {
        // Called when the system wants to interrupt the feedback our service is providing.
    }

    /**
     * Initializes an AudioRecord instance for concurrent capturing.
     * Uses VOICE_RECOGNITION and explicitly sets privacy sensitive to false for API 30+
     * to allow sharing the microphone stream with other apps like a system screen recorder.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @android.annotation.SuppressLint("MissingPermission")
    fun createAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSize: Int
    ): AudioRecord? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val builder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Crucial for concurrent capture with global screen recorders on One UI etc.
                builder.setPrivacySensitive(false)
            }
            
            builder.build()
        } else {
            // Fallback for older devices
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        }
    }
}
