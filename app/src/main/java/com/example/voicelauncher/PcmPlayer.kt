package com.example.voicelauncher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream

class PcmPlayer {

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    fun playPcm(inputStream: InputStream, sampleRate: Int = 24000, onCompletion: (() -> Unit)? = null) {
        try {
            val data = inputStream.readBytes()
            inputStream.close()
            if (data.isEmpty()) {
                Log.w("PcmPlayer", "Empty PCM data")
                onCompletion?.invoke()
                return
            }

            Log.d("PcmPlayer", "Playing ${data.size} bytes at ${sampleRate}Hz")

            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
            val bufferSize = maxOf(data.size, minBufferSize)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(data, 0, data.size)
            audioTrack?.setNotificationMarkerPosition(data.size / 2)
            audioTrack?.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) {
                    Log.d("PcmPlayer", "Playback complete")
                    stop()
                    onCompletion?.invoke()
                }
                override fun onPeriodicNotification(track: AudioTrack?) {}
            })

            audioTrack?.play()
            isPlaying = true
            Log.d("PcmPlayer", "Started playback")
        } catch (e: Exception) {
            Log.e("PcmPlayer", "Error playing PCM", e)
            stop()
            onCompletion?.invoke()
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
        isPlaying = false
    }
}
