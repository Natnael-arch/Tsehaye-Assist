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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean



class IntentDispatcher(private val context: Context) {

    private val contactProvider = ContactProvider(context)
    private val contactMatcher = ContactMatcher()
    private val usageStore = UsageStore(context)

    private val mainScope = MainScope()

    // Pending action gate — at most one at a time, expires after 60s
    private sealed class PendingAction {
        var createdAt: Long = System.currentTimeMillis()
        abstract val usageKey: String
        class Call(
            val contactName: String,
            val number: String,
            override val usageKey: String
        ) : PendingAction()
        class Sms(
            val contactName: String,
            val number: String,
            val messageBody: String,
            override val usageKey: String
        ) : PendingAction()
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
    private var lastDisambiguationCandidates: List<Contact>? = null
    private var lastSearchRawName: String? = null

    interface ToolCallback {
        fun sendToolResponse(callId: String, functionName: String, resultMap: Map<String, Any>)
        fun sendAmbiguity(callId: String, matches: List<String>)
        fun vibrate()
        fun announce(announcement: Announcement)
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
            "confirm_pending_action" -> {
                Log.i("IntentDispatcher", "✅ Gemini called confirm_pending_action")
                val acted = confirmPendingLocal(toolCallback)
                toolCallback.sendToolResponse(callId, "confirm_pending_action", mapOf(
                    "result" to pendingActionResult(acted)
                ))
            }
            "cancel_pending_action" -> {
                Log.i("IntentDispatcher", "🚫 Gemini called cancel_pending_action")
                val acted = cancelPendingLocal(toolCallback)
                toolCallback.sendToolResponse(callId, "cancel_pending_action", mapOf(
                    "result" to pendingActionResult(acted)
                ))
            }
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
            val resolved = ContactResolver.resolve(rawName, contacts, lastDisambiguationCandidates)
            Log.d("IntentDispatcher", "search_contacts path: ${resolved.path}")

            when (resolved.match) {
                is MatchResult.ExactMatch -> {
                    lastDisambiguationNames = null  // clear disambiguation state
                    lastDisambiguationCandidates = null
                    val contact = resolved.match.contact
                    toolCallback.vibrate()
                    pendingAction = PendingAction.Call(
                        contact.name,
                        contact.phoneNumber,
                        usageKeyFor(contact.contactId, contact.normalizedName, contact.normalizedNumber)
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
                    val names = resolved.match.labels
                    lastDisambiguationNames = names  // track what was offered
                    lastDisambiguationCandidates = resolved.match.candidates
                    Log.w("IntentDispatcher", "⚠️ DISAMBIGUATION: offering candidates $names for rawName='$rawName'")
                    toolCallback.vibrate()
                    toolCallback.sendAmbiguity(callId, names)
                }
                is MatchResult.NoMatch -> {
                    lastDisambiguationNames = null
                    lastDisambiguationCandidates = null
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
            val resolved = ContactResolver.resolve(recipientName, contacts)
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
                    pendingAction = PendingAction.Sms(
                        contact.name,
                        number,
                        messageBody,
                        usageKeyFor(contact.contactId, contact.normalizedName, contact.normalizedNumber)
                    )
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
                    val names = resolved.match.labels
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

    /** @return false when there was nothing to act on, or the pending action had already expired. */
    fun confirmPendingLocal(toolCallback: ToolCallback): Boolean {
        Log.i("IntentDispatcher", "━━━ CONFIRM PENDING ACTION (LOCAL) ━━━")
        Log.i("IntentDispatcher", "previousAwaitingCallId=$awaitingConfirmationCallId")
        Log.i("IntentDispatcher", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        awaitingConfirmationCallId = null
        val action = pendingAction
        if (action == null) {
            Log.w("IntentDispatcher", "⚠️ Local confirm but nothing pending")
            return false
        }
        val ageMs = System.currentTimeMillis() - action.createdAt
        if (ageMs > PENDING_ACTION_TTL_MS) {
            Log.w("IntentDispatcher", "⚠️ Pending action expired (${ageMs}ms old) — clearing")
            pendingAction = null
            return false
        }
        pendingAction = null
        when (action) {
            is PendingAction.Call -> {
                Log.i("IntentDispatcher", "📞 Executing confirmed call: ${action.contactName} (${action.number})")
                val placed = executeCall(action.contactName, action.number)
                toolCallback.announce(if (placed) Announcements.CALL_PLACED else Announcements.CALL_NOT_PLACED)
                // Only an action the user physically confirmed counts as usage — a search that
                // ended in PENDING_CONFIRMATION and was cancelled, or merely re-asked about, must not.
                usageStore.record(action.usageKey)
            }
            is PendingAction.Sms -> {
                Log.i("IntentDispatcher", "💬 Executing confirmed SMS: ${action.contactName} (${action.number})")
                // No toolResponse here: the originating callId was already closed by
                // PENDING_CONFIRMATION, so anything sent under a synthetic id is ignored by Gemini.
                // The spoken outcome is the only thing the user gets, so it must not be silent.
                executeSms(action.number, action.messageBody, action.contactName) { toolCallback.announce(it) }
                usageStore.record(action.usageKey)
            }
        }
        return true
    }

    /** @return false when a cancel arrived but no action was pending. */
    fun cancelPendingLocal(toolCallback: ToolCallback): Boolean {
        Log.i("IntentDispatcher", "━━━ CANCEL PENDING ACTION (LOCAL) ━━━")
        Log.i("IntentDispatcher", "previousAwaitingCallId=$awaitingConfirmationCallId")
        Log.i("IntentDispatcher", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        awaitingConfirmationCallId = null
        val action = pendingAction
        pendingAction = null
        if (action != null) {
            Log.i("IntentDispatcher", "🚫 Pending action cancelled locally by user")
            return true
        }
        Log.w("IntentDispatcher", "⚠️ Local cancel but nothing pending")
        return false
    }

    /** @return false when CALL_PHONE was unavailable and only the dialer could be opened. */
    private fun executeCall(contactName: String, phoneNumber: String): Boolean {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: SecurityException) {
            Log.e("IntentDispatcher", "CALL_PHONE denied — falling back to the dialer", e)
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            false
        }
    }

    private fun executeSms(
        number: String,
        messageBody: String,
        contactName: String,
        announce: (Announcement) -> Unit
    ) {
        mainScope.launch {
            var sentReceiver: BroadcastReceiver? = null
            var deliveredReceiver: BroadcastReceiver? = null
            val sentRegistered = AtomicBoolean(false)
            val deliveredRegistered = AtomicBoolean(false)

            fun unregisterWhich(receiver: BroadcastReceiver?, registered: AtomicBoolean) {
                receiver ?: return
                unregisterOnce(registered) {
                    runCatching { context.unregisterReceiver(receiver) }
                        .onFailure { Log.w("IntentDispatcher", "SMS receiver unregister failed: ${it.message}") }
                }
            }

            try {
                val smsManager = SmsManager.getDefault()
                val parts = smsManager.divideMessage(messageBody)
                val uuid = UUID.randomUUID().toString()
                val sentAction = "SMS_SENT_$uuid"
                val deliveredAction = "SMS_DELIVERED_$uuid"
                val sentSettled = AtomicBoolean(false)
                val deliveredSettled = AtomicBoolean(false)

                val sentPI = PendingIntent.getBroadcast(
                    context,
                    uuid.hashCode(),
                    Intent(sentAction).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val deliveredPI = PendingIntent.getBroadcast(
                    context,
                    uuid.hashCode() + 1,
                    Intent(deliveredAction).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                sentReceiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (!sentSettled.compareAndSet(false, true)) return
                        unregisterWhich(this, sentRegistered)
                        if (resultCode == Activity.RESULT_OK) {
                            Log.d("IntentDispatcher", "SMS sent successfully to $contactName ($number)")
                            announce(Announcements.SMS_SENT)
                        } else {
                            Log.e("IntentDispatcher", "SMS send failed, resultCode=$resultCode")
                            announce(Announcements.SMS_FAILED)
                        }
                    }
                }

                deliveredReceiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (!deliveredSettled.compareAndSet(false, true)) return
                        unregisterWhich(this, deliveredRegistered)
                        if (resultCode == Activity.RESULT_OK) {
                            Log.d("IntentDispatcher", "SMS delivered to $number")
                        } else {
                            Log.d("IntentDispatcher", "SMS not delivered to $number, resultCode=$resultCode")
                        }
                    }
                }

                ContextCompat.registerReceiver(
                    context, sentReceiver, IntentFilter(sentAction), ContextCompat.RECEIVER_NOT_EXPORTED
                )
                sentRegistered.set(true)
                ContextCompat.registerReceiver(
                    context, deliveredReceiver, IntentFilter(deliveredAction), ContextCompat.RECEIVER_NOT_EXPORTED
                )
                deliveredRegistered.set(true)

                val sentIntents = ArrayList<PendingIntent>()
                val deliveredIntents = ArrayList<PendingIntent>()
                for (i in 0 until parts.size) {
                    sentIntents.add(sentPI)
                    deliveredIntents.add(deliveredPI)
                }

                smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, deliveredIntents)
                Log.d("IntentDispatcher", "SMS sent to $contactName ($number): $messageBody")

                // Carrier callbacks can be dropped silently (dead number, no signal); this stops
                // the receivers from staying registered for the life of the process.
                val sentRef = sentReceiver
                val deliveredRef = deliveredReceiver
                mainScope.launch {
                    delay(SMS_CALLBACK_TIMEOUT_MS)
                    if (sentSettled.get() && deliveredSettled.get()) return@launch
                    Log.d("IntentDispatcher", "SMS callback window closed after ${SMS_CALLBACK_TIMEOUT_MS}ms")
                    unregisterWhich(sentRef, sentRegistered)
                    unregisterWhich(deliveredRef, deliveredRegistered)
                    // A dropped carrier callback is not proof of failure, so do not claim the message failed.
                    if (!sentSettled.get()) {
                        Log.w("IntentDispatcher", "No SMS sent callback — telling the user it is unconfirmed")
                        announce(Announcements.SMS_UNCONFIRMED)
                    }
                }
            } catch (e: Exception) {
                Log.e("IntentDispatcher", "SMS error", e)
                unregisterWhich(sentReceiver, sentRegistered)
                unregisterWhich(deliveredReceiver, deliveredRegistered)
                announce(Announcements.SMS_FAILED)
            }
        }
    }

    companion object {
        private const val PENDING_ACTION_TTL_MS = 10_000L
        private const val SMS_CALLBACK_TIMEOUT_MS = 30_000L

        // A stale confirm must not answer OK, or the model announces an action that never happened.
        fun pendingActionResult(acted: Boolean): String = if (acted) "OK" else "NOTHING_PENDING"

        /**
         * Runs [unregister] only if the receiver is still registered, and clears the flag first so
         * concurrent callers can't both reach unregisterReceiver(). The SMS callbacks unregister
         * themselves, so without this the 30s sweep double-unregisters and the platform throws
         * "Receiver not registered".
         *
         * @return true if [unregister] ran
         */
        fun unregisterOnce(registered: AtomicBoolean, unregister: () -> Unit): Boolean {
            if (!registered.compareAndSet(true, false)) return false
            unregister()
            return true
        }
    }
}
