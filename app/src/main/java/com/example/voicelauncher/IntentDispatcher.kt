package com.example.voicelauncher

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import java.util.*

object AmharicCleaner {
    fun clean(input: String): String {
        return input.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .trim()
    }
}

class IntentDispatcher(private val context: Context) {

    private val contactProvider = ContactProvider(context)
    private val contactMatcher = ContactMatcher()

    private val mainScope = MainScope()

    // Pending action gate — at most one at a time, expires after 60s
    private sealed class PendingAction {
        var createdAt: Long = System.currentTimeMillis()
        class Call(val contactName: String, val number: String) : PendingAction()
        class Sms(val contactName: String, val number: String, val messageBody: String) : PendingAction()
    }

    private var pendingAction: PendingAction? = null

    val isAwaitingConfirmation: Boolean
        get() = pendingAction != null

    fun resetPendingActionTimer() {
        pendingAction?.createdAt = System.currentTimeMillis()
    }

    // Track whether we're currently awaiting Gemini's confirm/cancel after a PENDING_CONFIRMATION
    @Volatile var awaitingConfirmationCallId: String? = null
        private set

    // ── DIAGNOSTIC: Track search_contacts call sequence ──
    private var searchCallCounter = 0
    private var lastDisambiguationNames: List<String>? = null
    private var lastSearchRawName: String? = null

    interface ToolCallback {
        fun sendToolResponse(callId: String, functionName: String, resultMap: Map<String, Any>)
        fun sendAmbiguity(callId: String, matches: List<String>)
        fun vibrate()
    }

    data class ResolvedContact(val match: MatchResult, val path: String)

    fun resolveContactFromDualScriptQuery(rawName: String, contacts: List<Contact>): ResolvedContact {
        Log.w("IntentDispatcher", "┌── resolveContactFromDualScriptQuery ──")
        Log.w("IntentDispatcher", "│  rawName='$rawName'")

        var parts = rawName.split(Regex("[,|/]")).map { it.trim() }.filter { it.isNotEmpty() }
        Log.w("IntentDispatcher", "│  split by [,|/] → parts=$parts")
        if (parts.size < 2) {
            parts = rawName.split(" ").map { it.trim() }.filter { it.isNotEmpty() }
            Log.w("IntentDispatcher", "│  split by space → parts=$parts")
        }

        val rawAmharic = parts.firstOrNull { p -> p.any { it in '\u1200'..'\u137F' } }
                         ?: parts.firstOrNull() ?: rawName

        val rawLatin = parts.lastOrNull { p ->
            p.any { it in 'A'..'z' } && p != rawAmharic
        } ?: if (parts.size > 1) parts.last() else null

        val amharicQuery = AmharicCleaner.clean(rawAmharic)
        val latinQuery = rawLatin?.trim()

        Log.w("IntentDispatcher", "│  rawAmharic='$rawAmharic' → cleaned='$amharicQuery'")
        Log.w("IntentDispatcher", "│  rawLatin='$rawLatin' → trimmed='$latinQuery'")

        if (amharicQuery.isNotBlank()) {
            Log.w("IntentDispatcher", "│  ATTEMPTING amharic path with query='$amharicQuery'")
            val match = contactMatcher.findBestMatches(amharicQuery, contacts)
            if (match !is MatchResult.NoMatch) {
                Log.w("IntentDispatcher", "└── RESOLVED via amharic path")
                return ResolvedContact(match, "amharic:$amharicQuery")
            }
            Log.w("IntentDispatcher", "│  amharic path → NoMatch, falling through")
        }

        if (!latinQuery.isNullOrBlank()) {
            Log.w("IntentDispatcher", "│  ATTEMPTING latin path with query='$latinQuery'")
            val match = contactMatcher.findBestMatches(latinQuery, contacts)
            if (match !is MatchResult.NoMatch) {
                Log.w("IntentDispatcher", "└── RESOLVED via latin path")
                return ResolvedContact(match, "latin:$latinQuery")
            }
            Log.w("IntentDispatcher", "│  latin path → NoMatch")
        }

        val usedQuery = amharicQuery.ifBlank { latinQuery } ?: "unknown"
        Log.w("IntentDispatcher", "└── RESOLVED: NoMatch (both paths failed)")
        return ResolvedContact(MatchResult.NoMatch, "none:$usedQuery")
    }

    fun handleToolCall(
        callId: String,
        functionName: String,
        args: Map<String, Any>,
        toolCallback: ToolCallback
    ) {
        Log.i("IntentDispatcher", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i("IntentDispatcher", "🔧 TOOL CALL RECEIVED: $functionName")
        Log.i("IntentDispatcher", "   CallId: $callId | Args: $args")
        Log.i("IntentDispatcher", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        when (functionName) {
            "search_contacts" -> handleSearchContacts(callId, args, toolCallback)
            "add_new_contact" -> handleAddNewContact(callId, args, toolCallback)
            "send_text_message" -> handleSendTextMessage(callId, args, toolCallback)
            else -> {
                Log.w("IntentDispatcher", "⚠️ Unknown function: $functionName — sending empty response")
                toolCallback.sendToolResponse(callId, functionName, mapOf("result" to "UNKNOWN_FUNCTION"))
            }
        }
    }

    private fun handleSearchContacts(
        callId: String,
        args: Map<String, Any>,
        toolCallback: ToolCallback
    ) {
        if (awaitingConfirmationCallId != null) {
            Log.w("IntentDispatcher", "Ignored duplicate tool call while awaiting local confirmation: search_contacts")
            val action = pendingAction
            if (action is PendingAction.Call) {
                toolCallback.sendToolResponse(callId, "search_contacts", mapOf(
                    "result" to "PENDING_CONFIRMATION",
                    "name" to action.contactName,
                    "number" to action.number
                ))
            } else if (action is PendingAction.Sms) {
                toolCallback.sendToolResponse(callId, "search_contacts", mapOf(
                    "result" to "PENDING_CONFIRMATION",
                    "name" to action.contactName,
                    "number" to action.number,
                    "message_body" to action.messageBody
                ))
            }
            return
        }
        val rawName = args["name"] as? String
        searchCallCounter++
        val callNum = searchCallCounter

        // ── DIAGNOSTIC: Log raw input and call sequence ──
        Log.w("IntentDispatcher", "╔══ search_contacts CALL #$callNum ══")
        Log.w("IntentDispatcher", "║  callId=$callId")
        Log.w("IntentDispatcher", "║  rawName (from Gemini)='$rawName'")
        Log.w("IntentDispatcher", "║  previousRawName='$lastSearchRawName'")
        if (lastDisambiguationNames != null) {
            Log.w("IntentDispatcher", "║  ⚠️ DISAMBIGUATION WAS ACTIVE — last candidates: $lastDisambiguationNames")
            Log.w("IntentDispatcher", "║  ⚠️ Gemini's follow-up name='$rawName'")
            val isExactRepeat = rawName == lastSearchRawName
            val matchesCandidate = lastDisambiguationNames?.any { it.equals(rawName, ignoreCase = true) } == true
            Log.w("IntentDispatcher", "║  ⚠️ isExactRepeatOfOriginalQuery=$isExactRepeat")
            Log.w("IntentDispatcher", "║  ⚠️ matchesOneOfTheCandidates=$matchesCandidate")
        } else {
            Log.w("IntentDispatcher", "║  (no prior disambiguation active — fresh search)")
        }
        Log.w("IntentDispatcher", "╚══════════════════════════════════════")

        lastSearchRawName = rawName

        if (rawName.isNullOrBlank()) {
            toolCallback.sendToolResponse(callId, "search_contacts", mapOf("result" to "NO_NAME_PROVIDED"))
            return
        }

        mainScope.launch {
            val contacts = withContext(Dispatchers.IO) { contactProvider.fetchContacts() }
            val resolved = resolveContactFromDualScriptQuery(rawName, contacts)
            Log.d("IntentDispatcher", "search_contacts path: ${resolved.path}")

            when (resolved.match) {
                is MatchResult.ExactMatch -> {
                    lastDisambiguationNames = null  // clear disambiguation state
                    toolCallback.vibrate()
                    pendingAction = PendingAction.Call(
                        resolved.match.contact.name,
                        resolved.match.contact.phoneNumber
                    )
                    awaitingConfirmationCallId = callId
                    Log.i("IntentDispatcher", "📞 Pending call stored: ${resolved.match.contact.name} — waiting for local confirmation")
                    val pendingResultMap = mapOf(
                        "result" to "PENDING_CONFIRMATION",
                        "name" to resolved.match.contact.name,
                        "number" to resolved.match.contact.phoneNumber
                    )
                    Log.w("IntentDispatcher", "━━━ PENDING_CONFIRMATION RESPONSE (search_contacts) ━━━")
                    Log.w("IntentDispatcher", "callId=$callId")
                    Log.w("IntentDispatcher", "resultMap keys=${pendingResultMap.keys}")
                    Log.w("IntentDispatcher", "resultMap['result']='${pendingResultMap["result"]}'")
                    Log.w("IntentDispatcher", "resultMap['name']='${pendingResultMap["name"]}'")
                    Log.w("IntentDispatcher", "resultMap['number']='${pendingResultMap["number"]}'")
                    Log.w("IntentDispatcher", "FULL resultMap=$pendingResultMap")
                    Log.w("IntentDispatcher", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    toolCallback.sendToolResponse(callId, "search_contacts", pendingResultMap)
                }
                is MatchResult.DisambiguationRequired -> {
                    val names = resolved.match.candidates.map { it.name }
                    lastDisambiguationNames = names  // track what was offered
                    Log.w("IntentDispatcher", "⚠️ DISAMBIGUATION: offering candidates $names for rawName='$rawName'")
                    toolCallback.vibrate()
                    toolCallback.sendAmbiguity(callId, names)
                }
                is MatchResult.NoMatch -> {
                    lastDisambiguationNames = null
                    toolCallback.sendToolResponse(callId, "search_contacts", mapOf(
                        "result" to "NOT_FOUND",
                        "query" to resolved.path
                    ))
                }
            }
        }
    }

    private fun handleAddNewContact(
        callId: String,
        args: Map<String, Any>,
        toolCallback: ToolCallback
    ) {
        val name = args["name"] as? String
        val phoneNumber = args["phone_number"] as? String
        if (name.isNullOrBlank() || phoneNumber.isNullOrBlank()) {
            toolCallback.sendToolResponse(callId, "add_new_contact", mapOf("result" to "MISSING_ARGS"))
            return
        }
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            toolCallback.sendToolResponse(callId, "add_new_contact", mapOf(
                "result" to "PERMISSION_DENIED",
                "permission" to "WRITE_CONTACTS"
            ))
            return
        }

        mainScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val ops = ArrayList<ContentProviderOperation>()
                    val (accountType, accountName) = if (Build.MANUFACTURER.equals("samsung", true))
                        "vnd.sec.contact.phone" to "Phone" else null to null

                    ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue("account_type", accountType)
                        .withValue("account_name", accountName)
                        .build())
                    ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValueBackReference("raw_contact_id", 0)
                        .withValue("mimetype", "vnd.android.cursor.item/name")
                        .withValue("data1", name)
                        .build())
                    ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValueBackReference("raw_contact_id", 0)
                        .withValue("mimetype", "vnd.android.cursor.item/phone_v2")
                        .withValue("data1", phoneNumber)
                        .withValue("data2", 2)
                        .build())

                    context.contentResolver.applyBatch("com.android.contacts", ops)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Contact saved: $name", Toast.LENGTH_SHORT).show()
                    toolCallback.vibrate()
                    toolCallback.sendToolResponse(callId, "add_new_contact", mapOf(
                        "result" to "SAVED",
                        "name" to name,
                        "phone_number" to phoneNumber
                    ))
                }
            } catch (e: Exception) {
                Log.e("IntentDispatcher", "Error adding contact", e)
                withContext(Dispatchers.Main) {
                    toolCallback.sendToolResponse(callId, "add_new_contact", mapOf(
                        "result" to "ERROR",
                        "message" to (e.message ?: "Unknown error")
                    ))
                }
            }
        }
    }

    private fun handleSendTextMessage(
        callId: String,
        args: Map<String, Any>,
        toolCallback: ToolCallback
    ) {
        if (awaitingConfirmationCallId != null) {
            Log.w("IntentDispatcher", "Ignored duplicate tool call while awaiting local confirmation: send_text_message")
            val action = pendingAction
            if (action is PendingAction.Call) {
                toolCallback.sendToolResponse(callId, "send_text_message", mapOf(
                    "result" to "PENDING_CONFIRMATION",
                    "name" to action.contactName,
                    "number" to action.number
                ))
            } else if (action is PendingAction.Sms) {
                toolCallback.sendToolResponse(callId, "send_text_message", mapOf(
                    "result" to "PENDING_CONFIRMATION",
                    "name" to action.contactName,
                    "number" to action.number,
                    "message_body" to action.messageBody
                ))
            }
            return
        }
        val recipientName = args["recipient_name"] as? String
        val messageBody = args["message_body"] as? String
        if (recipientName.isNullOrBlank() || messageBody.isNullOrBlank()) {
            toolCallback.sendToolResponse(callId, "send_text_message", mapOf("result" to "MISSING_ARGS"))
            return
        }
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            toolCallback.sendToolResponse(callId, "send_text_message", mapOf(
                "result" to "PERMISSION_DENIED",
                "permission" to "SEND_SMS"
            ))
            return
        }

        mainScope.launch {
            val contacts = withContext(Dispatchers.IO) { contactProvider.fetchContacts() }
            val resolved = resolveContactFromDualScriptQuery(recipientName, contacts)
            Log.d("IntentDispatcher", "send_text_message path: ${resolved.path}")

            when (resolved.match) {
                is MatchResult.ExactMatch -> {
                    val contact = resolved.match.contact
                    val number = contact.phoneNumber.replace(Regex("[^0-9+]"), "")
                    if (number.isEmpty()) {
                        toolCallback.sendToolResponse(callId, "send_text_message", mapOf(
                            "result" to "ERROR",
                            "message" to "Contact has no valid phone number"
                        ))
                        return@launch
                    }
                    toolCallback.vibrate()
                    pendingAction = PendingAction.Sms(contact.name, number, messageBody)
                    awaitingConfirmationCallId = callId
                    Log.i("IntentDispatcher", "💬 Pending SMS stored: ${contact.name} ($number) — waiting for local confirmation")
                    val pendingSmsResultMap = mapOf(
                        "result" to "PENDING_CONFIRMATION",
                        "name" to contact.name,
                        "number" to number,
                        "message_body" to messageBody
                    )
                    Log.w("IntentDispatcher", "━━━ PENDING_CONFIRMATION RESPONSE (send_text_message) ━━━")
                    Log.w("IntentDispatcher", "callId=$callId")
                    Log.w("IntentDispatcher", "resultMap keys=${pendingSmsResultMap.keys}")
                    Log.w("IntentDispatcher", "resultMap['result']='${pendingSmsResultMap["result"]}'")
                    Log.w("IntentDispatcher", "resultMap['name']='${pendingSmsResultMap["name"]}'")
                    Log.w("IntentDispatcher", "resultMap['number']='${pendingSmsResultMap["number"]}'")
                    Log.w("IntentDispatcher", "FULL resultMap=$pendingSmsResultMap")
                    Log.w("IntentDispatcher", "EXPECTING Gemini to call confirm_pending_action or cancel_pending_action next")
                    Log.w("IntentDispatcher", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    toolCallback.sendToolResponse(callId, "send_text_message", pendingSmsResultMap)
                }
                is MatchResult.DisambiguationRequired -> {
                    val names = resolved.match.candidates.map { it.name }
                    toolCallback.sendToolResponse(callId, "send_text_message", mapOf(
                        "result" to "AMBIGUITY",
                        "matches" to names
                    ))
                }
                is MatchResult.NoMatch -> {
                    toolCallback.sendToolResponse(callId, "send_text_message", mapOf(
                        "result" to "NOT_FOUND",
                        "query" to recipientName
                    ))
                }
            }
        }
    }

    fun confirmPendingLocal(toolCallback: ToolCallback) {
        Log.i("IntentDispatcher", "━━━ CONFIRM PENDING ACTION (LOCAL) ━━━")
        Log.i("IntentDispatcher", "previousAwaitingCallId=$awaitingConfirmationCallId")
        Log.i("IntentDispatcher", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        awaitingConfirmationCallId = null
        val action = pendingAction
        if (action == null) {
            Log.w("IntentDispatcher", "⚠️ Local confirm but nothing pending")
            return
        }
        val ageMs = System.currentTimeMillis() - action.createdAt
        if (ageMs > PENDING_ACTION_TTL_MS) {
            Log.w("IntentDispatcher", "⚠️ Pending action expired (${ageMs}ms old) — clearing")
            pendingAction = null
            return
        }
        pendingAction = null
        when (action) {
            is PendingAction.Call -> {
                Log.i("IntentDispatcher", "📞 Executing confirmed call: ${action.contactName} (${action.number})")
                executeCall(action.contactName, action.number)
            }
            is PendingAction.Sms -> {
                Log.i("IntentDispatcher", "💬 Executing confirmed SMS: ${action.contactName} (${action.number})")
                // Pass a dummy callId since this is local
                executeSms(action.number, action.messageBody, "local_confirm", action.contactName, toolCallback)
            }
        }
    }

    fun cancelPendingLocal(toolCallback: ToolCallback) {
        Log.i("IntentDispatcher", "━━━ CANCEL PENDING ACTION (LOCAL) ━━━")
        Log.i("IntentDispatcher", "previousAwaitingCallId=$awaitingConfirmationCallId")
        Log.i("IntentDispatcher", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        awaitingConfirmationCallId = null
        val action = pendingAction
        pendingAction = null
        if (action != null) {
            Log.i("IntentDispatcher", "🚫 Pending action cancelled locally by user")
        } else {
            Log.w("IntentDispatcher", "⚠️ Local cancel but nothing pending")
        }
    }

    private fun executeCall(contactName: String, phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: SecurityException) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
        }
    }

    private fun executeSms(
        number: String,
        messageBody: String,
        callId: String,
        contactName: String,
        toolCallback: ToolCallback
    ) {
        mainScope.launch {
            try {
                val smsManager = SmsManager.getDefault()
                val parts = smsManager.divideMessage(messageBody)
                val sentIntents = ArrayList<PendingIntent>()
                val deliveredIntents = ArrayList<PendingIntent>()
                val uuid = UUID.randomUUID().toString()
                val sentAction = "SMS_SENT_$uuid"
                val deliveredAction = "SMS_DELIVERED_$uuid"

                val sentReceiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (resultCode == Activity.RESULT_OK) {
                            Log.d("IntentDispatcher", "SMS sent successfully")
                            toolCallback.sendToolResponse(callId, "send_text_message", mapOf(
                                "result" to "SENT", "name" to contactName, "number" to number
                            ))
                        } else {
                            Log.e("IntentDispatcher", "SMS send failed, resultCode=$resultCode")
                            toolCallback.sendToolResponse(callId, "send_text_message", mapOf(
                                "result" to "ERROR", "message" to "SMS delivery failed (resultCode=$resultCode)"
                            ))
                        }
                    }
                }

                val deliveredReceiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        Log.d("IntentDispatcher", "SMS delivered to $number")
                    }
                }

                context.registerReceiver(sentReceiver, IntentFilter(sentAction))
                context.registerReceiver(deliveredReceiver, IntentFilter(deliveredAction))

                val sentPI = PendingIntent.getBroadcast(context, uuid.hashCode(), Intent(sentAction),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val deliveredPI = PendingIntent.getBroadcast(context, uuid.hashCode() + 1, Intent(deliveredAction),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                for (i in 0 until parts.size) {
                    sentIntents.add(sentPI)
                    deliveredIntents.add(deliveredPI)
                }

                smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, deliveredIntents)
                Log.d("IntentDispatcher", "SMS sent to $contactName ($number): $messageBody")
            } catch (e: Exception) {
                Log.e("IntentDispatcher", "SMS error", e)
                toolCallback.sendToolResponse(callId, "send_text_message", mapOf(
                    "result" to "ERROR", "message" to (e.message ?: "SMS failed")
                ))
            }
        }
    }

    companion object {
        private const val PENDING_ACTION_TTL_MS = 10_000L
    }
}
