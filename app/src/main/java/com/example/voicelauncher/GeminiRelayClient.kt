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

class GeminiRelayClient(private val url: String, private val identity: DeviceIdentity? = null) {

    private val TAG = "GeminiRelayClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isConnecting = false
    private var reconnectAttempts = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var shouldReconnect = true
    private var lastContactNames: List<String> = emptyList()
    private var nextConnectionId = 0
    private var liveSockets = 0
    private var terminalRefusal: Int? = null
    private var lastAnnouncedRefusal: Int? = null

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

    var onPairing: ((status: String, reason: String, tester: String) -> Unit)? = null
    var onRefusal: ((code: Int, reason: String) -> Unit)? = null

    fun resetAudioDroppedFlag() {
        audioDroppedSignaled = false
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectHandler.removeCallbacksAndMessages(null)
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
            if (shouldReconnect && !isConnected && !isConnecting) {
                Log.d(TAG, "Reconnect attempt $reconnectAttempts (delay was ${delayMs}ms)")
                connect()
            }
        }, delayMs)
    }

    private fun handleRefusal(code: Int, reason: String) {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectAttempts = 0
        val delayMs = RelayClosePolicy.retryDelayMs(code)
        if (delayMs == null) {
            terminalRefusal = code
            shouldReconnect = false
            Log.e(TAG, "TERMINAL REFUSAL code=$code reason=$reason — no reconnect until the app restarts")
        } else {
            Log.w(TAG, "REFUSAL code=$code reason=$reason — retrying in ${delayMs}ms")
            reconnectHandler.postDelayed({
                if (!isConnected && !isConnecting) connect()
            }, delayMs)
        }
        // One spoken announcement per episode, not one per retry: the user is told once and then left in peace.
        if (lastAnnouncedRefusal != code) {
            lastAnnouncedRefusal = code
            onRefusal?.invoke(code, reason)
        }
    }

    fun connect(contactNames: List<String> = emptyList()) {
        terminalRefusal?.let {
            Log.w(TAG, "Connect blocked by terminal refusal $it — the host must fix the pairing first")
            return
        }
        if (isConnected || isConnecting) {
            Log.d(TAG, "Already connected or connecting")
            return
        }

        Log.d(TAG, "Connecting to $url")
        val connectionId = ++nextConnectionId
        Log.d(TAG, "CONNECT id=$connectionId t=${System.currentTimeMillis()}")
        if (contactNames.isNotEmpty()) {
            lastContactNames = contactNames
        }
        val namesUsed = if (contactNames.isNotEmpty()) contactNames else lastContactNames
        val requestBuilder = Request.Builder().url(url)
        val base64Contacts = if (namesUsed.isNotEmpty()) {
            val jsonArray = org.json.JSONArray(namesUsed)
            Base64.encodeToString(jsonArray.toString().toByteArray(), Base64.NO_WRAP)
        } else {
            ""
        }
        if (base64Contacts.isNotEmpty()) {
            requestBuilder.header("X-Tsehaye-Contacts", base64Contacts)
        }
        identity?.let { id ->
            if (id.deviceId.isEmpty()) {
                Log.e(TAG, "ANDROID_ID is empty — the relay will refuse this phone")
            } else {
                val token = id.token()
                requestBuilder.header("X-Tsehaye-Device", id.deviceId)
                requestBuilder.header("X-Tsehaye-Signature", id.signatureHash)
                requestBuilder.header("X-Tsehaye-App-Version", id.appVersion)
                if (token.isNotEmpty()) requestBuilder.header("X-Tsehaye-Token", token)
                Log.d(TAG, "IDENTITY device=${id.deviceId} sig=${id.signatureHash.take(8)} version=${id.appVersion} tokenPresent=${token.isNotEmpty()}")
            }
        }
        Log.d(TAG, "Connecting with ${namesUsed.size} contacts, header chars=${base64Contacts.length}")

        val request = requestBuilder.build()

        shouldReconnect = true
        isConnecting = true
        var opened = false
        var refusalHandled = false
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                audioDroppedSignaled = false
                lastAnnouncedRefusal = null
                opened = true
                liveSockets++
                Log.d(TAG, "OPEN id=$connectionId liveSockets=$liveSockets t=${System.currentTimeMillis()}")
                if (liveSockets > 1) {
                    Log.e(TAG, "⚠️⚠️ MORE THAN ONE LIVE RELAY SOCKET liveSockets=$liveSockets id=$connectionId")
                }
                Log.d(TAG, "Connected to relay server")
                isConnecting = false
                isConnected = true
                callback?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "pairing") {
                        // Logged without the token: a bearer credential must not land in logcat.
                        val token = json.optString("token", "")
                        identity?.saveToken(token)
                        val status = json.optString("status", "")
                        val reason = json.optString("reason", "")
                        val tester = json.optString("tester", "")
                        Log.i(TAG, "PAIRING status=$status reason=$reason tester=$tester tokenIssued=${token.isNotEmpty()}")
                        onPairing?.invoke(status, reason, tester)
                        return
                    }
                    Log.d(TAG, "Text message: ${text.take(200)}")
                    if (text.contains("functionCall") || text.contains("functionResponse") || text.contains("PENDING")) {
                        Log.w(TAG, "━━━ RAW INCOMING RELAY MESSAGE ━━━")
                        Log.w(TAG, text)
                        Log.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    }
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
                if (RelayClosePolicy.isRefusal(code) && !refusalHandled) {
                    refusalHandled = true
                    handleRefusal(code, reason)
                }
                webSocket.close(code, reason)
                isConnected = false
                callback?.onDisconnected(reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "CLOSED id=$connectionId code=$code reason=$reason liveSockets=$liveSockets t=${System.currentTimeMillis()}")
                if (opened) {
                    liveSockets = (liveSockets - 1).coerceAtLeast(0)
                }
                isConnecting = false
                isConnected = false
                callback?.onDisconnected(reason)
                if (!refusalHandled && code != 1000) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "FAILURE id=$connectionId msg=${t.message} liveSockets=$liveSockets t=${System.currentTimeMillis()}")
                if (opened) {
                    liveSockets = (liveSockets - 1).coerceAtLeast(0)
                }
                isConnecting = false
                isConnected = false
                callback?.onFailure(t.message ?: "Unknown WebSocket error")
                if (!refusalHandled) scheduleReconnect()
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

    fun sendActivityStart() {
        if (!isConnected) {
            Log.w(TAG, "Cannot send activityStart: not connected")
            return
        }
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("activityStart", JSONObject())
            })
        }
        sendMessage(msg)
        Log.d(TAG, "Sent activityStart signal")
    }

    fun sendActivityEnd() {
        if (!isConnected) {
            Log.w(TAG, "Cannot send activityEnd: not connected")
            return
        }
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("activityEnd", JSONObject())
            })
        }
        sendMessage(msg)
        Log.d(TAG, "Sent activityEnd signal")
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Disconnecting")
        webSocket?.close(1000, "Client disconnect")
        isConnecting = false
        isConnected = false
    }

    fun isConnected(): Boolean = isConnected

    fun hasContacts(): Boolean = lastContactNames.isNotEmpty()
}
