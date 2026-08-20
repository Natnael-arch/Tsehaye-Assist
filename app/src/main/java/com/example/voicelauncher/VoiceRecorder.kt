package com.example.voicelauncher

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

class VoiceRecorder(private val cacheDir: File) {

    constructor(context: Context) : this(context.cacheDir)

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 4

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    var outputFile: File? = null
        private set

    var onAmplitudeListener: ((Int) -> Unit)? = null
    var onAudioDataListener: ((ByteArray, Int) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) stopRecording()

        outputFile = File(cacheDir, "voice_command.wav")
        if (outputFile?.exists() == true) outputFile?.delete()
        
        audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && AccessibilityAudioService.instance != null) {
            Log.d("VoiceRecorder", "Using AccessibilityAudioService for AudioRecord")
            AccessibilityAudioService.instance?.createAudioRecord(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )
        } else {
            Log.d("VoiceRecorder", "Using fallback standard AudioRecord")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("VoiceRecorder", "AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isRecording = true
        Log.d("VoiceRecorder", "Started recording to ${outputFile?.absolutePath}")

        recordingThread = Thread {
            writeAudioDataToFile()
        }.apply { start() }
    }

    fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
        Log.d("VoiceRecorder", "Stopped recording")
    }

    private fun writeAudioDataToFile() {
        val data = ByteArray(BUFFER_SIZE)
        val pcmFile = File(cacheDir, "temp.pcm")
        var os: FileOutputStream? = null

        try {
            os = FileOutputStream(pcmFile)
            while (isRecording) {
                val read = audioRecord?.read(data, 0, BUFFER_SIZE) ?: 0
                if (read > 0) {
                    os.write(data, 0, read)
                    
                    var maxAmplitude = 0
                    for (i in 0 until read step 2) {
                        if (i + 1 < read) {
                            val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)
                            val amplitude = Math.abs(sample.toShort().toInt())
                            if (amplitude > maxAmplitude) {
                                maxAmplitude = amplitude
                            }
                        }
                    }
                    onAmplitudeListener?.invoke(maxAmplitude)
                    onAudioDataListener?.invoke(data, read)
                }
            }
            os.close()
            Log.d("VoiceRecorder", "PCM data written, size: ${pcmFile.length()} bytes")
            // Convert Raw PCM to WAV
            outputFile?.let { convertPcmToWav(pcmFile, it) }
            Log.d("VoiceRecorder", "WAV file created, size: ${outputFile?.length()} bytes")
            pcmFile.delete() // Clean up raw pcm
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Could not write audio to file", e)
        } finally {
            try { os?.close() } catch (e: Exception) {}
        }
    }

    // Helper to add WAV Header to PCM data
    private fun convertPcmToWav(pcmFile: File, wavFile: File) {
        val pcmSize = pcmFile.length()
        val inStream = pcmFile.inputStream()
        val outStream = RandomAccessFile(wavFile, "rw")

        // Write WAV Header
        writeWavHeader(outStream, pcmSize, SAMPLE_RATE, 1, 16)
        
        // Write Audio Data
        val buffer = ByteArray(BUFFER_SIZE)
        var length: Int
        while (inStream.read(buffer).also { length = it } > 0) {
            outStream.write(buffer, 0, length)
        }
        
        inStream.close()
        outStream.setLength(outStream.filePointer) // Truncate the file to the actual size
        outStream.close()
    }

    private fun writeWavHeader(out: RandomAccessFile, pcmDataLength: Long, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val overallSize = pcmDataLength + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        out.seek(0)
        out.write("RIFF".toByteArray())
        out.writeInt(Integer.reverseBytes(overallSize.toInt()))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.writeInt(Integer.reverseBytes(16)) // subchunk1size (16 for PCM)
        out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // audioFormat (1 for PCM)
        out.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
        out.writeInt(Integer.reverseBytes(sampleRate))
        out.writeInt(Integer.reverseBytes(byteRate))
        out.writeShort(java.lang.Short.reverseBytes((channels * bitsPerSample / 8).toShort()).toInt()) // blockAlign
        out.writeShort(java.lang.Short.reverseBytes(bitsPerSample.toShort()).toInt())
        out.write("data".toByteArray())
        out.writeInt(Integer.reverseBytes(pcmDataLength.toInt()))
    }
}
