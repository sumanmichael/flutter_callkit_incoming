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

internal class CallOwnership {
    private val outgoingClaims = mutableSetOf<String>()
    private val active = mutableMapOf<String, Any>()

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
    fun activate(callId: String, owner: Any): Boolean {
        outgoingClaims.remove(callId)
        if (callId in active) return active[callId] === owner
        active[callId] = owner
        return true
    }

    @Synchronized
    fun owner(callId: String): Any? = active[callId]

    @Synchronized
    fun finish(callId: String, owner: Any): Boolean = active.remove(callId, owner)

    @Synchronized
    fun clear() {
        outgoingClaims.clear()
        active.clear()
    }

    @Synchronized
    fun activeCount(): Int = active.size
}

@RequiresApi(Build.VERSION_CODES.M)
class InAppCallManager(private val context: Context) {

    companion object {
        private const val ACCOUNT_ID = "flutter_callkit_incoming_in_app_call_account"
        private const val TAG = "InAppCallManager"
    }

    fun registerPhoneAccount(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val builder = PhoneAccount.builder(getPhoneAccountHandle(), "VSP Phone")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            if (supportsSelfManagedCallHistory(Build.VERSION.SDK_INT)) {
                builder.setExtras(Bundle().apply {
                    putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, true)
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
        } catch (_: Exception) {
            CallkitConnection.cancelOutgoing(parsed.id)
            Log.w(TAG, "Outgoing Telecom call unavailable.")
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
