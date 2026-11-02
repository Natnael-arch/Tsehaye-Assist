package com.example.voicelauncher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.InputStream

class PcmPlayer {

    companion object {
        private const val TAG = "PcmPlayer"
        private var liveTracks = 0
    }

    private data class Clip(
        val data: ByteArray,
        val sampleRate: Int,
        val turnId: Int,
        val onCompletion: (() -> Unit)?
    )

    private val queue = ArrayDeque<Clip>()
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    var turnId: Int = -1
    private var activeTurnId: Int = -1

    fun playPcm(inputStream: InputStream, sampleRate: Int = 24000, onCompletion: (() -> Unit)? = null) {
        val data: ByteArray
        try {
            data = inputStream.readBytes()
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading PCM", e)
            onCompletion?.invoke()
            return
        }
        if (data.isEmpty()) {
            Log.w(TAG, "Empty PCM data")
            onCompletion?.invoke()
            return
        }

        val clip = Clip(data, sampleRate, turnId, onCompletion)
        if (isPlaying || audioTrack != null) {
            queue.addLast(clip)
            Log.d(TAG, "QUEUED turn=${clip.turnId} bytes=${data.size} queue=${queue.size}")
            return
        }
        startClip(clip)
    }

    private fun startClip(clip: Clip) {
        try {
            Log.d(TAG, "Playing ${clip.data.size} bytes at ${clip.sampleRate}Hz turn=${clip.turnId}")

            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(clip.sampleRate, channelConfig, encoding)
            val bufferSize = maxOf(clip.data.size, minBufferSize)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(clip.sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            audioTrack = track
            activeTurnId = clip.turnId
            liveTracks++
            Log.d(TAG, "AUDIOTRACK create live=$liveTracks turn=$activeTurnId")
            if (liveTracks > 1) {
                Log.e(TAG, "⚠️⚠️ MORE THAN ONE LIVE AUDIOTRACK live=$liveTracks turn=$activeTurnId")
            }

            track.write(clip.data, 0, clip.data.size)
            track.setNotificationMarkerPosition(clip.data.size / 2)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(reachedTrack: AudioTrack?) {
                    if (audioTrack !== reachedTrack) {
                        Log.d(TAG, "IGNORED late playback marker turn=${clip.turnId} (not current track)")
                        return
                    }
                    Log.d(TAG, "Playback complete turn=$activeTurnId")
                    releaseTrack("completed")
                    clip.onCompletion?.invoke()
                    startNext()
                }
                override fun onPeriodicNotification(track: AudioTrack?) {}
            })

            track.play()
            isPlaying = true
            Log.d(TAG, "Started playback turn=$activeTurnId")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing PCM", e)
            releaseTrack("interrupted")
            isPlaying = false
            clip.onCompletion?.invoke()
            startNext()
        }
    }

    private fun startNext() {
        if (isPlaying || audioTrack != null) return
        val next = queue.removeFirstOrNull()
        if (next == null) {
            Log.d(TAG, "QUEUE empty")
            return
        }
        Log.d(TAG, "DEQUEUE turn=${next.turnId} bytes=${next.data.size} queue=${queue.size}")
        startClip(next)
    }

    private fun releaseTrack(reason: String) {
        val track = audioTrack
        if (track != null) {
            val releasedTurn = activeTurnId
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {}
            liveTracks = (liveTracks - 1).coerceAtLeast(0)
            Log.d(TAG, "AUDIOTRACK release live=$liveTracks turn=$releasedTurn reason=$reason")
        }
        audioTrack = null
        isPlaying = false
        activeTurnId = -1
    }

    fun stop(reason: String = "stopped-by-next") {
        val cleared = queue.size
        queue.clear()
        if (audioTrack != null) {
            Log.i(TAG, "PLAY stop turn=$activeTurnId t=${System.currentTimeMillis()} reason=$reason queueCleared=$cleared")
        }
        releaseTrack(reason)
    }
}
