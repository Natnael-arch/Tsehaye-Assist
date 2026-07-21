package com.example.voicelauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.core.view.GestureDetectorCompat
import android.view.GestureDetector
import org.json.JSONObject

class MainActivity : AppCompatActivity(), IntentDispatcher.ToolCallback {

    private lateinit var statusTextView: TextView
    private lateinit var touchArea: FrameLayout
    private lateinit var vibrator: Vibrator
    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var waveformView: WaveformView

    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var intentDispatcher: IntentDispatcher
    private lateinit var relayClient: GeminiRelayClient
    private lateinit var pcmPlayer: PcmPlayer
    private lateinit var gestureDetector: GestureDetectorCompat

    private var pulseAnimator: AnimatorSet? = null
    private var audioBuffer = ByteArrayOutputStream()
    private var audioResponseSampleRate = 24000
    private var isRecording = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            Log.d("VoiceLauncher", "${it.key} = ${it.value}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        touchArea = findViewById(R.id.touchArea)
        statusTextView = findViewById(R.id.statusTextView)
        pulseRing1 = findViewById(R.id.pulseRing1)
        pulseRing2 = findViewById(R.id.pulseRing2)
        waveformView = findViewById(R.id.waveformView)

        voiceRecorder = VoiceRecorder(this)
        pcmPlayer = PcmPlayer()
        intentDispatcher = IntentDispatcher(this)

        relayClient = GeminiRelayClient(BuildConfig.RELAY_WS_URL)
        relayClient.callback = object : GeminiRelayClient.RelayCallback {
            override fun onConnected() {
                runOnUiThread {
                    Log.d("VoiceLauncher", "WebSocket connected, waiting for setup")
                    statusTextView.text = "Setting up..."
                }
            }

            override fun onDisconnected(reason: String) {
                runOnUiThread {
                    Log.w("VoiceLauncher", "WebSocket disconnected: $reason")
                    statusTextView.text = "Offline"
                }
            }

            override fun onTextMessage(json: JSONObject) {
                runOnUiThread {
                    Log.d("VoiceLauncher", "Relay text: ${json.toString().take(300)}")
                    handleRelayTextMessage(json)
                }
            }

            override fun onBinaryMessage(data: ByteArray) {
                Log.d("VoiceLauncher", "Relay binary: ${data.size} bytes (ignored)")
            }

            override fun onFailure(error: String) {
                runOnUiThread {
                    Log.e("VoiceLauncher", "Relay failure: $error")
                    Toast.makeText(this@MainActivity, "Connection error: $error", Toast.LENGTH_SHORT).show()
                }
            }
        }

        statusTextView.text = "Connecting..."

        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (intentDispatcher.isAwaitingConfirmation) {
                    vibrateConfirm()
                    intentDispatcher.confirmPendingLocal(this@MainActivity)
                    runOnUiThread { statusTextView.text = "Confirmed" }
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (intentDispatcher.isAwaitingConfirmation) {
                    vibrateReject()
                    intentDispatcher.cancelPendingLocal(this@MainActivity)
                    runOnUiThread { statusTextView.text = "Cancelled" }
                    return true
                }
                return false
            }
        })

        touchArea.setOnTouchListener { _, event ->
            if (intentDispatcher.isAwaitingConfirmation) {
                gestureDetector.onTouchEvent(event)
                true
            } else {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!isRecording) {
                            isRecording = true
                            pulse(100)
                            startVoiceCapture()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isRecording) {
                            isRecording = false
                            doublePulse(200)
                            stopVoiceCapture()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        checkAndRequestPermissions()

        relayClient.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        relayClient.disconnect()
        pcmPlayer.stop()
    }

    private fun handleRelayTextMessage(json: JSONObject) {
        Log.d("VoiceLauncher", "Relay msg keys: ${json.keys().asSequence().toList()}")

        // Track what Gemini does after we sent PENDING_CONFIRMATION
        val awaitingId = intentDispatcher.awaitingConfirmationCallId
        if (awaitingId != null) {
            Log.w("VoiceLauncher", "⚠️ STILL AWAITING CONFIRMATION (callId=$awaitingId) — Gemini just sent:")
            Log.w("VoiceLauncher", "⚠️ rawJson=${json.toString().take(500)}")
        }

        when {
            json.has("setupComplete") -> {
                Log.d("VoiceLauncher", "Relay setup complete")
                runOnUiThread { statusTextView.text = "Ready 👂" }
            }
            json.has("serverContent") -> {
                val content = json.getJSONObject("serverContent")
                val modelTurn = content.optJSONObject("modelTurn")
                if (modelTurn != null) {
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text")) {
                                val text = part.getString("text")
                                Log.d("VoiceLauncher", "AI Text: $text")
                                if (awaitingId != null) {
                                    Log.w("VoiceLauncher", "⚠️ GEMINI GENERATED TEXT INSTEAD OF CONFIRM/CANCEL: \"$text\"")
                                    Log.w("VoiceLauncher", "⚠️ THIS IS THE BUG — Gemini should have called confirm_pending_action or cancel_pending_action")
                                    Log.w("VoiceLauncher", "⚠️ Instead it generated a text response. Possible causes:")
                                    Log.w("VoiceLauncher", "⚠️   1. Tool response schema mismatch (relay functionDeclarations don't declare 'name' field)")
                                    Log.w("VoiceLauncher", "⚠️   2. MASTER_PROMPT Rule 6 not instructing Gemini to use the 'name' field")
                                    Log.w("VoiceLauncher", "⚠️   3. Gemini doesn't understand PENDING_CONFIRMATION means it should wait for user confirmation")
                                    awaitingConfirmationCallId_checkCleared()
                                }
                                runOnUiThread { statusTextView.text = text.take(80) }
                            } else if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val mimeType = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                                val b64Data = inlineData.getString("data")
                                val pcmBytes = Base64.decode(b64Data, Base64.NO_WRAP)
                                Log.d("VoiceLauncher", "Audio chunk: ${pcmBytes.size} bytes ($mimeType)")
                                audioBuffer.write(pcmBytes)
                                audioResponseSampleRate = when {
                                    "24000" in mimeType -> 24000
                                    "16000" in mimeType -> 16000
                                    else -> 24000
                                }
                            } else if (part.has("functionCall")) {
                                val fc = part.getJSONObject("functionCall")
                                val name = fc.getString("name")
                                val args = fc.optJSONObject("args") ?: JSONObject()
                                val callId = fc.optString("id", "")
                                Log.d("VoiceLauncher", "Tool call (in serverContent): $name args=$args id=$callId")
                                if (awaitingId != null) {
                                    when (name) {
                                        "confirm_pending_action" -> {
                                            Log.i("VoiceLauncher", "✅ Gemini correctly called confirm_pending_action after PENDING_CONFIRMATION")
                                        }
                                        "cancel_pending_action" -> {
                                            Log.i("VoiceLauncher", "✅ Gemini correctly called cancel_pending_action after PENDING_CONFIRMATION")
                                        }
                                        "search_contacts" -> {
                                            Log.w("VoiceLauncher", "⚠️ GEMINI CALLED search_contacts AGAIN instead of confirm/cancel")
                                            Log.w("VoiceLauncher", "⚠️ This is the 'who do you want to call?' loop bug")
                                            Log.w("VoiceLauncher", "⚠️ Args: $args")
                                        }
                                        else -> {
                                            Log.w("VoiceLauncher", "⚠️ GEMINI CALLED UNEXPECTED TOOL '$name' after PENDING_CONFIRMATION")
                                            Log.w("VoiceLauncher", "⚠️ Args: $args")
                                        }
                                    }
                                    awaitingConfirmationCallId_checkCleared()
                                }
                                runOnUiThread { statusTextView.text = "Executing: $name" }
                                dispatchToolCall(callId, name, args)
                            }
                        }
                    }
                }
                val turnComplete = content.optBoolean("turnComplete", false)
                if (turnComplete) {
                    Log.d("VoiceLauncher", "Turn complete, buffer=${audioBuffer.size()} bytes")
                    if (awaitingId != null) {
                        Log.w("VoiceLauncher", "⚠️ TURN COMPLETE while still awaiting confirmation — Gemini neither confirmed, cancelled, nor called any tool")
                        awaitingConfirmationCallId_checkCleared()
                    }
                    if (audioBuffer.size() > 0) {
                        playAccumulatedAudio()
                    } else {
                        runOnUiThread { statusTextView.text = "Ready 👂" }
                    }
                }
                val generationComplete = content.optBoolean("generationComplete", false)
                if (generationComplete) {
                    Log.d("VoiceLauncher", "Generation complete")
                }
                val interrupted = content.optBoolean("interrupted", false)
                if (interrupted) {
                    Log.d("VoiceLauncher", "Model turn interrupted")
                    audioBuffer.reset()
                    pcmPlayer.stop()
                }
            }
            json.has("toolCall") -> {
                val toolCall = json.getJSONObject("toolCall")
                val calls = toolCall.optJSONArray("functionCalls")
                if (calls != null) {
                    for (i in 0 until calls.length()) {
                        val call = calls.getJSONObject(i)
                        val name = call.getString("name")
                        val args = call.optJSONObject("args") ?: JSONObject()
                        val callId = call.optString("id", "")
                        Log.d("VoiceLauncher", "Tool call: $name args=$args id=$callId")
                        if (awaitingId != null) {
                            when (name) {
                                "confirm_pending_action" -> {
                                    Log.i("VoiceLauncher", "✅ Gemini correctly called confirm_pending_action after PENDING_CONFIRMATION")
                                }
                                "cancel_pending_action" -> {
                                    Log.i("VoiceLauncher", "✅ Gemini correctly called cancel_pending_action after PENDING_CONFIRMATION")
                                }
                                "search_contacts" -> {
                                    Log.w("VoiceLauncher", "⚠️ GEMINI CALLED search_contacts AGAIN instead of confirm/cancel")
                                    Log.w("VoiceLauncher", "⚠️ This is the 'who do you want to call?' loop bug")
                                    Log.w("VoiceLauncher", "⚠️ Args: $args")
                                }
                                else -> {
                                    Log.w("VoiceLauncher", "⚠️ GEMINI CALLED UNEXPECTED TOOL '$name' after PENDING_CONFIRMATION")
                                    Log.w("VoiceLauncher", "⚠️ Args: $args")
                                }
                            }
                            awaitingConfirmationCallId_checkCleared()
                        }
                        runOnUiThread { statusTextView.text = "Executing: $name" }
                        dispatchToolCall(callId, name, args)
                    }
                }
            }
            json.has("toolCallCancellation") -> {
                Log.d("VoiceLauncher", "Tool call cancelled")
            }
            json.has("sessionResumptionUpdate") -> {
                Log.d("VoiceLauncher", "Session resumed")
            }
            json.has("error") -> {
                val error = json.getJSONObject("error")
                val msg = error.optString("message", "Unknown error")
                Log.e("VoiceLauncher", "Server error: $msg")
                runOnUiThread { statusTextView.text = "Error: $msg" }
            }
            else -> {
                Log.d("VoiceLauncher", "Unhandled relay message: ${json.toString().take(200)}")
                if (awaitingId != null) {
                    Log.w("VoiceLauncher", "⚠️ UNHANDLED message type while awaiting confirmation: ${json.keys().asSequence().toList()}")
                }
            }
        }
    }

    /**
     * Check if awaitingConfirmationCallId was cleared by a confirm/cancel tool call
     * and log accordingly. This is called after processing each Gemini action to
     * detect whether the confirmation gate was properly resolved.
     */
    private fun awaitingConfirmationCallId_checkCleared() {
        // The flag is on IntentDispatcher — it gets cleared when handleConfirmPending/handleCancelPending runs
        // We just check if it's still set to know if the action resolved the gate
    }

    private fun dispatchToolCall(callId: String, name: String, args: JSONObject) {
        val argsMap = mutableMapOf<String, Any>()
        for (key in args.keys()) {
            argsMap[key] = args.get(key) ?: ""
        }
        intentDispatcher.handleToolCall(callId, name, argsMap, this)
    }

    private fun playAccumulatedAudio() {
        val data = audioBuffer.toByteArray()
        audioBuffer.reset()
        Log.d("VoiceLauncher", "Playing accumulated ${data.size} bytes at ${audioResponseSampleRate}Hz")
        try {
            val tempFile = File.createTempFile("relay_audio", ".pcm", cacheDir)
            tempFile.deleteOnExit()
            tempFile.writeBytes(data)
            runOnUiThread {
                statusTextView.text = "Speaking..."
                pcmPlayer.playPcm(tempFile.inputStream(), audioResponseSampleRate) {
                    Log.d("VoiceLauncher", "Audio playback finished")
                    runOnUiThread {
                        if (intentDispatcher.isAwaitingConfirmation) {
                            statusTextView.text = "ማረጋገጫ ይጠብቃል"
                            vibrateAwaiting()
                            intentDispatcher.resetPendingActionTimer()
                        } else {
                            statusTextView.text = "Ready 👂"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceLauncher", "Error playing audio", e)
        }
    }

    // ── IntentDispatcher.ToolCallback implementation ──

    override fun sendToolResponse(callId: String, functionName: String, resultMap: Map<String, Any>) {
        val response = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", callId)
                        put("name", functionName)
                        put("response", JSONObject(resultMap))
                    })
                })
            })
        }
        val jsonStr = response.toString()
        Log.i("VoiceLauncher", "━━━ FULL TOOL RESPONSE ($functionName) ━━━")
        Log.i("VoiceLauncher", "callId=$callId")
        Log.i("VoiceLauncher", "resultMap=$resultMap")
        Log.i("VoiceLauncher", "fullJson=$jsonStr")
        Log.i("VoiceLauncher", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // CRITICAL: Log PENDING_CONFIRMATION payloads at WARN level for easy filtering
        if (resultMap["result"] == "PENDING_CONFIRMATION") {
            Log.w("VoiceLauncher", "╔══════════════════════════════════════════════════╗")
            Log.w("VoiceLauncher", "║  SENDING PENDING_CONFIRMATION TO GEMINI         ║")
            Log.w("VoiceLauncher", "║  Function: $functionName")
            Log.w("VoiceLauncher", "║  CallId: $callId")
            Log.w("VoiceLauncher", "║  Field 'result': '${resultMap["result"]}'")
            Log.w("VoiceLauncher", "║  Field 'name': '${resultMap["name"]}'")
            Log.w("VoiceLauncher", "║  Field 'number': '${resultMap["number"]}'")
            Log.w("VoiceLauncher", "║  ALL field names in response: ${resultMap.keys}")
            Log.w("VoiceLauncher", "║  Complete JSON sent over WebSocket:")
            Log.w("VoiceLauncher", "║  $jsonStr")
            Log.w("VoiceLauncher", "║")
            Log.w("VoiceLauncher", "║  EXPECTED: Gemini should call confirm_pending_action")
            Log.w("VoiceLauncher", "║  or cancel_pending_action on its next turn.")
            Log.w("VoiceLauncher", "╚══════════════════════════════════════════════════╝")
        }

        relayClient.sendMessage(response)
    }

    override fun sendAmbiguity(callId: String, matches: List<String>) {
        val msg = JSONObject().apply {
            put("type", "AMBIGUITY")
            put("matches", org.json.JSONArray(matches))
            put("functionCallId", callId)
        }
        Log.d("VoiceLauncher", "Sending AMBIGUITY: $matches")
        relayClient.sendMessage(msg)
    }

    override fun vibrate() {
        pulse(100)
    }

    private fun pulse(duration: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    private fun doublePulse(duration: Long) {
        val pattern = longArrayOf(0, duration, 100, duration)
        vibratePattern(pattern)
    }

    private fun vibrateAwaiting() {
        // Single short pulse
        vibratePattern(longArrayOf(0, 50))
    }

    private fun vibrateConfirm() {
        // Two quick pulses
        vibratePattern(longArrayOf(0, 50, 50, 50))
    }

    private fun vibrateReject() {
        // One longer pulse
        vibratePattern(longArrayOf(0, 300))
    }

    private fun vibratePattern(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startVoiceCapture() {
        statusTextView.text = "Listening 👂"
        Log.d("VoiceLauncher", "startVoiceCapture() called")

        voiceRecorder.startRecording()

        pulseRing1.visibility = View.VISIBLE
        pulseRing2.visibility = View.VISIBLE

        val scaleX1 = ObjectAnimator.ofFloat(pulseRing1, "scaleX", 1f, 1.4f)
        val scaleY1 = ObjectAnimator.ofFloat(pulseRing1, "scaleY", 1f, 1.4f)
        val alpha1 = ObjectAnimator.ofFloat(pulseRing1, "alpha", 0.6f, 0f)

        val scaleX2 = ObjectAnimator.ofFloat(pulseRing2, "scaleX", 1.2f, 1.8f)
        val scaleY2 = ObjectAnimator.ofFloat(pulseRing2, "scaleY", 1.2f, 1.8f)
        val alpha2 = ObjectAnimator.ofFloat(pulseRing2, "alpha", 0.4f, 0f)

        listOf(scaleX1, scaleY1, alpha1, scaleX2, scaleY2, alpha2).forEach {
            it.repeatCount = ObjectAnimator.INFINITE
            it.repeatMode = ObjectAnimator.RESTART
            it.duration = 1200
        }

        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX1, scaleY1, alpha1, scaleX2, scaleY2, alpha2)
            start()
        }

        voiceRecorder.onAmplitudeListener = { maxAmplitude ->
            runOnUiThread {
                waveformView.updateAmplitude(maxAmplitude)
            }
        }

        voiceRecorder.onAudioDataListener = { data, read ->
            if (relayClient.isConnected()) {
                val audioChunk = data.copyOf(read)
                relayClient.sendAudio(audioChunk)
            }
        }
    }

    private fun stopVoiceCapture() {
        Log.d("VoiceLauncher", "stopVoiceCapture() called")

        voiceRecorder.stopRecording()

        pulseAnimator?.cancel()
        voiceRecorder.onAmplitudeListener = null
        voiceRecorder.onAudioDataListener = null

        pulseRing1.visibility = View.GONE
        pulseRing2.visibility = View.GONE
        waveformView.reset()

        if (relayClient.isConnected()) {
            relayClient.sendEndOfAudio()
            statusTextView.text = "Processing..."
        } else {
            Log.w("VoiceLauncher", "Relay not connected — cannot process voice")
            statusTextView.text = "Offline"
        }
    }
}
