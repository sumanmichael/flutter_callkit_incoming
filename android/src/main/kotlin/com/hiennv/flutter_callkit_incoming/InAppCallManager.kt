package com.hiennv.flutter_callkit_incoming

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

internal fun supportsSelfManagedCallHistory(sdkInt: Int): Boolean = sdkInt >= 28

// Null leaves the extra off: below API 28 it does not exist, and the
// transactional path does not read it.
internal fun selfManagedCallLogExtra(sdkInt: Int, transactional: Boolean, enabled: Boolean): Boolean? =
    if (transactional || !supportsSelfManagedCallHistory(sdkInt)) null else enabled

/** False where the setting cannot change anything, so an app can hide it. */
internal fun systemCallLogSupported(sdkInt: Int, transactional: Boolean): Boolean =
    selfManagedCallLogExtra(sdkInt, transactional, true) != null

internal class CallOwnership {
    private val outgoingClaims = mutableSetOf<String>()
    private data class OwnedCall(val sessionKey: String?, val owner: Any)
    private val active = mutableMapOf<String, OwnedCall>()

    @Synchronized
    fun claimOutgoing(callId: String): Boolean =
        callId.isNotEmpty() && callId !in active && outgoingClaims.add(callId)

    @Synchronized
    fun hasOutgoingClaim(callId: String): Boolean = callId in outgoingClaims

    @Synchronized
    fun cancelOutgoing(callId: String) {
        outgoingClaims.remove(callId)
    }

    @Synchronized
    fun activate(callId: String, sessionKey: String?, owner: Any): Boolean {
        outgoingClaims.remove(callId)
        if (callId in active) return active[callId]?.owner === owner
        active[callId] = OwnedCall(sessionKey, owner)
        return true
    }

    @Synchronized
    fun owner(callId: String): Any? = active[callId]?.owner

    @Synchronized
    fun dispatch(callId: String, sessionKey: String?, action: (Any) -> Unit): Boolean {
        val owned = active[callId] ?: return false
        if (owned.sessionKey != sessionKey) return false
        action(owned.owner)
        return true
    }

    @Synchronized
    fun finish(callId: String, owner: Any): Boolean {
        if (active[callId]?.owner !== owner) return false
        active.remove(callId)
        return true
    }

    @Synchronized
    fun clear() {
        outgoingClaims.clear()
        active.clear()
    }

    @Synchronized
    fun activeCount(): Int = active.size
}

internal class TelecomEventRouter(private val sendToOwner: (String, String?) -> Unit) {
    private val callbacksSent = mutableSetOf<String>()
    private val ownerEvents = mutableSetOf<String>()

    @Synchronized
    fun fromTelecom(action: String, outcome: String? = null): Boolean {
        val key = eventKey(action)
        if (key in callbacksSent || key in ownerEvents || isBlockedByTerminal(key)) return false
        callbacksSent += key
        sendToOwner(action, outcome)
        return true
    }

    @Synchronized
    fun fromOwner(action: String, outcome: String? = null): Boolean {
        val key = eventKey(action)
        if (key in ownerEvents || isBlockedByTerminal(key)) return false
        ownerEvents += key
        return true
    }

    private fun isBlockedByTerminal(key: String): Boolean =
        key != TERMINAL && (TERMINAL in callbacksSent || TERMINAL in ownerEvents)

    private fun eventKey(action: String): String = when (action) {
        CallkitConstants.ACTION_CALL_DECLINE,
        CallkitConstants.ACTION_CALL_ENDED,
        CallkitConstants.ACTION_CALL_TIMEOUT -> TERMINAL
        else -> action
    }

    private companion object {
        const val TERMINAL = "terminal"
    }
}

@RequiresApi(Build.VERSION_CODES.M)
class InAppCallManager(private val context: Context) {

    companion object {
        private const val ACCOUNT_ID = "flutter_callkit_incoming_in_app_call_account"
        private const val TAG = "InAppCallManager"

        // Set when the call log setting changed under a live call; the last
        // call's end registers the account again instead of the next start.
        @Volatile
        private var registrationPending = false

        fun deferRegistration() {
            registrationPending = true
        }

        fun registerIfPending(context: Context) {
            if (!registrationPending) return
            registrationPending = false
            PendingCallEvents.reportPhoneAccountRegistration(
                InAppCallManager(context.applicationContext).registerPhoneAccount(),
            )
        }
    }

    fun registerPhoneAccount(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val capabilities = if (usesTransactionalTelecom()) {
                PhoneAccount.CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS
            } else {
                PhoneAccount.CAPABILITY_SELF_MANAGED
            }
            val builder = PhoneAccount.builder(getPhoneAccountHandle(), "VSP Phone")
                .setCapabilities(capabilities)
            selfManagedCallLogExtra(
                Build.VERSION.SDK_INT,
                usesTransactionalTelecom(),
                isSystemCallLogEnabled(context),
            )?.let { logCalls ->
                builder.setExtras(Bundle().apply {
                    putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, logCalls)
                })
            }
            telecomManager.registerPhoneAccount(builder.build())
            Log.d(TAG, "PhoneAccount registered.")
            true
        } catch (_: Exception) {
            Log.w(TAG, "PhoneAccount registration unavailable.")
            false
        }
    }

    fun placeOutgoingCall(data: Bundle): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val parsed = try {
            Data.fromBundle(data)
        } catch (_: Exception) {
            return false
        }
        if (parsed.id.isEmpty() || parsed.handle.isBlank() || !CallkitConnection.claimOutgoing(parsed.id)) return false

        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val account = telecomManager.getPhoneAccount(getPhoneAccountHandle())
            if (account == null ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_OWN_CALLS) != PackageManager.PERMISSION_GRANTED
            ) {
                CallkitConnection.cancelOutgoing(parsed.id)
                Log.w(TAG, "Outgoing Telecom call skipped: account=${account != null}")
                false
            } else {
                val ownedExtras = Bundle().apply {
                    putBundle(CallkitConnection.EXTRA_CALL_BUNDLE, data)
                }
                val extras = Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, getPhoneAccountHandle())
                    putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, ownedExtras)
                    putBundle(CallkitConnection.EXTRA_CALL_BUNDLE, data)
                }
                telecomManager.placeCall(Uri.fromParts("tel", parsed.handle, null), extras)
                true
            }
        } catch (error: Exception) {
            CallkitConnection.cancelOutgoing(parsed.id)
            // Digits masked: a Telecom message can carry the dialled number.
            Log.w(
                TAG,
                "Outgoing Telecom call unavailable: ${error.javaClass.name}: " +
                    "${error.message?.replace(Regex("\\d"), "#")}",
            )
            false
        }
    }

    fun unregisterPhoneAccount() {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val componentName = ComponentName(context, CallkitConnectionService::class.java)
        val handle = PhoneAccountHandle(componentName, ACCOUNT_ID)

        telecomManager.unregisterPhoneAccount(handle)
        Log.d(TAG, "PhoneAccount unregistered.")
    }

    fun getPhoneAccountHandle(): PhoneAccountHandle {
        return PhoneAccountHandle(
            ComponentName(context, CallkitConnectionService::class.java),
            ACCOUNT_ID
        )
    }
}
