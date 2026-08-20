package com.example.voicelauncher

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper

class GeminiRelayClient(private val url: String) {

    private val TAG = "GeminiRelayClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var shouldReconnect = true

    interface RelayCallback {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onTextMessage(json: JSONObject)
        fun onBinaryMessage(data: ByteArray)
        fun onFailure(error: String)
    }

    var callback: RelayCallback? = null
    
    var onAudioDropped: (() -> Unit)? = null
    private var audioDroppedSignaled = false

    fun resetAudioDroppedFlag() {
        audioDroppedSignaled = false
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val delayMs = when (reconnectAttempts) {
            0 -> 1_000L
            1 -> 2_000L
            2 -> 4_000L
            3 -> 8_000L
            4 -> 16_000L
            else -> 30_000L
        }
        reconnectAttempts++
        reconnectHandler.postDelayed({
            if (shouldReconnect && !isConnected) {
                Log.d(TAG, "Reconnect attempt $reconnectAttempts (delay was ${delayMs}ms)")
                connect()
            }
        }, delayMs)
    }

    fun connect(contactNames: List<String> = emptyList()) {
        if (isConnected) {
            Log.d(TAG, "Already connected")
            return
        }

        Log.d(TAG, "Connecting to $url")
        val requestBuilder = Request.Builder().url(url)
        
        if (contactNames.isNotEmpty()) {
            val jsonArray = org.json.JSONArray(contactNames)
            val base64Contacts = Base64.encodeToString(jsonArray.toString().toByteArray(), Base64.NO_WRAP)
            requestBuilder.header("X-Tsehaye-Contacts", base64Contacts)
        }
        
        val request = requestBuilder.build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                audioDroppedSignaled = false
                Log.d(TAG, "Connected to relay server")
                isConnected = true
                callback?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Text message: ${text.take(200)}")
                // Log full incoming messages at WARN for confirmation gate debugging
                if (text.contains("functionCall") || text.contains("functionResponse") || text.contains("PENDING")) {
                    Log.w(TAG, "━━━ RAW INCOMING RELAY MESSAGE ━━━")
                    Log.w(TAG, text)
                    Log.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                }
                try {
                    val json = JSONObject(text)
                    callback?.onTextMessage(json)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse text message as JSON", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Binary message: ${bytes.size} bytes")
                callback?.onBinaryMessage(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closing: code=$code reason=$reason")
                webSocket.close(code, reason)
                isConnected = false
                callback?.onDisconnected(reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: code=$code reason=$reason")
                isConnected = false
                callback?.onDisconnected(reason)
                if (code != 1000) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected = false
                callback?.onFailure(t.message ?: "Unknown WebSocket error")
                scheduleReconnect()
            }
        })
    }

    fun sendAudio(pcmData: ByteArray) {
        if (!isConnected) {
            Log.w(TAG, "Cannot send audio: not connected")
            if (!audioDroppedSignaled) {
                audioDroppedSignaled = true
                onAudioDropped?.invoke()
            }
            return
        }
        val b64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", b64)
                    })
                })
            })
        }
        val sent = webSocket?.send(msg.toString()) ?: false
        if (!sent) {
            Log.w(TAG, "Failed to send audio frame")
            if (!audioDroppedSignaled) {
                audioDroppedSignaled = true
                onAudioDropped?.invoke()
            }
        }
    }

    fun sendMessage(json: JSONObject) {
        if (!isConnected) {
            Log.w(TAG, "Cannot send message: not connected")
            return
        }
        // Log tool responses at WARN level for debugging confirmation gate
        if (json.has("toolResponse")) {
            Log.w(TAG, "━━━ SENDING TOOL RESPONSE OVER WEBSOCKET ━━━")
            Log.w(TAG, "rawPayload=${json.toString()}")
            Log.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
        val sent = webSocket?.send(json.toString()) ?: false
        if (!sent) {
            Log.w(TAG, "Failed to send text message")
        }
    }

    fun sendEndOfAudio() {
        val msg = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turnComplete", true)
            })
        }
        sendMessage(msg)
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Disconnecting")
        webSocket?.close(1000, "Client disconnect")
        isConnected = false
    }

    fun isConnected(): Boolean = isConnected
}
