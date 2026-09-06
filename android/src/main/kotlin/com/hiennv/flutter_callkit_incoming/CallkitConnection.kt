package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Self-managed Telecom [Connection] implementation for flutter_callkit_incoming.
 *
 * Hosts the call in the Android Telecom framework with `PROPERTY_SELF_MANAGED` so
 * the OS treats it as a first-party phone call — granting it keyguard-bypass /
 * full-screen-intent priority on strict OEMs (Samsung Knox, Xiaomi, etc.) that
 * otherwise ignore [android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED]
 * on ordinary Activities.
 *
 * Lifecycle:
 *  1. [CallkitConnectionService.onCreateIncomingConnection] returns a
 *     ringing Connection instance.
 *  2. User taps Accept in our notification → plugin's BroadcastReceiver maps the
 *     broadcast to the matching connection via [find] and calls [setActive].
 *  3. User taps Decline / End → [setDisconnected] + [destroy] → unregister.
 *
 * Connection lookup uses a process-wide registry keyed by the call id
 * (the plugin's existing `Data.id` field). The OS holds the Connection object in
 * the Telecom framework; we keep a parallel reference so our BroadcastReceiver
 * can drive state transitions.
 */
@RequiresApi(Build.VERSION_CODES.M)
class CallkitConnection(
    private val context: Context,
    val callId: String,
    val bundle: Bundle,
) : Connection() {
    private val historyScope = bundle.getString(PendingCallEvents.scopeExtra)
    private val historySession = bundle.getString(PendingCallEvents.sessionExtra)
    private val historyDirection = bundle.getString(PendingCallEvents.directionExtra, "inbound")
    private val historyRemote = bundle.getString(CallkitConstants.EXTRA_CALLKIT_HANDLE, "")
    private val eventRouter = TelecomEventRouter { action, outcome ->
        val eventBundle = Bundle(bundle)
        outcome?.let { eventBundle.putString(EXTRA_HISTORY_OUTCOME, it) }
        context.sendBroadcast(CallkitIncomingBroadcastReceiver.getIntent(context, action, eventBundle))
    }

    companion object {
        private const val TAG = "CallkitConnection"

        /** Bundle key — pass the full call Data bundle through Telecom extras. */
        const val EXTRA_CALL_BUNDLE = "com.hiennv.flutter_callkit_incoming.CALL_BUNDLE"
        const val EXTRA_HISTORY_OUTCOME = "com.hiennv.flutter_callkit_incoming.HISTORY_OUTCOME"

        private val ownership = CallOwnership()

        fun find(callId: String): CallkitConnection? = ownership.owner(callId) as? CallkitConnection

        fun register(callId: String, sessionKey: String?, conn: CallkitConnection): Boolean =
            ownership.activate(callId, sessionKey, conn)

        fun claimOutgoing(callId: String): Boolean = ownership.claimOutgoing(callId)

        fun hasOutgoingClaim(callId: String): Boolean = ownership.hasOutgoingClaim(callId)

        fun cancelOutgoing(callId: String) = ownership.cancelOutgoing(callId)

        fun unregister(callId: String, conn: CallkitConnection) = ownership.finish(callId, conn)

        fun drive(
            callId: String,
            sessionKey: String?,
            context: Context,
            action: String,
            outcome: String? = null,
        ): Boolean? {
            if (ownership.owner(callId) == null) return null
            var applied = false
            val matched = ownership.dispatch(callId, sessionKey) { owner ->
                applied = (owner as CallkitConnection).driveFromOwner(context, action, outcome)
            }
            return matched && applied
        }

        /** For testing / cleanup — release all refs (Connection objects already destroyed by OS). */
        fun clearAll() {
            ownership.clear()
        }

        fun activeCount(): Int = ownership.activeCount()
    }

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD
        register(callId, historySession, this)
        Log.d(TAG, "Connection created id=$callId active=${activeCount()}")
    }

    // -------------------------------------------------------------------------
    // Telecom → app lifecycle callbacks
    //
    // These fire when the user interacts with the OS-level call UI (system
    // dialer, car Bluetooth, watch, etc.). In our app-driven model we still
    // handle the primary Accept/Decline via our own notification buttons, but
    // the OS can also trigger these — we must honor both paths.
    // -------------------------------------------------------------------------

    override fun onAnswer() {
        super.onAnswer()
        Log.d(TAG, "onAnswer id=$callId")
        eventRouter.fromTelecom(CallkitConstants.ACTION_CALL_ACCEPT)
    }

    override fun onReject() {
        super.onReject()
        Log.d(TAG, "onReject id=$callId")
        eventRouter.fromTelecom(CallkitConstants.ACTION_CALL_DECLINE)
    }

    override fun onDisconnect() {
        super.onDisconnect()
        Log.d(TAG, "onDisconnect id=$callId")
        eventRouter.fromTelecom(CallkitConstants.ACTION_CALL_ENDED)
    }

    override fun onAbort() {
        super.onAbort()
        Log.d(TAG, "onAbort id=$callId")
        eventRouter.fromTelecom(CallkitConstants.ACTION_CALL_ENDED, "failed")
    }

    override fun onHold() {
        super.onHold()
        setOnHold()
    }

    /**
     * While a Telecom call is ringing, volume-key presses never reach
     * AudioService — PhoneWindowManager intercepts them and calls
     * [android.telecom.TelecomManager.silenceRinger], which lands here.
     * Self-managed connections do their own ringing, so stop our ringtone.
     */
    override fun onSilence() {
        super.onSilence()
        Log.d(TAG, "onSilence id=$callId")
        FlutterCallkitIncomingPlugin.getInstance()?.getCallkitSoundPlayerManager()?.stop()
    }

    override fun onUnhold() {
        super.onUnhold()
        setActive()
    }

    // -------------------------------------------------------------------------
    // App → Telecom driving helpers (invoked by the plugin's BroadcastReceiver)
    // -------------------------------------------------------------------------

    private fun driveFromOwner(context: Context, action: String, outcome: String?): Boolean {
        if (!eventRouter.fromOwner(action, outcome)) return false
        when (action) {
            CallkitConstants.ACTION_CALL_ACCEPT -> markAccepted()
            CallkitConstants.ACTION_CALL_DECLINE -> markDeclined(context)
            CallkitConstants.ACTION_CALL_ENDED -> markEnded(outcome)
            CallkitConstants.ACTION_CALL_TIMEOUT -> markMissed()
            CallkitConstants.ACTION_CALL_CONNECTED -> markConnected()
        }
        return true
    }

    /** Mark the call as answered — user accepted via app notification. */
    fun markAccepted() {
        recordHistory("accepted")
        Log.d(TAG, "markAccepted id=$callId")
        setActive()
    }

    fun markConnected() {
        recordHistory("connected")
        setActive()
    }

    /**
     * Mark the call as declined/ended — user declined via app notification.
     *
     * Cold-launch DECLINE recovery is fired
     * from inside the self-managed [Connection] context (and *before*
     * [setDisconnected] runs) so the call is still in the RINGING state when
     * [Context.startActivity] is invoked. The hope: Android 14+ BAL grants a
     * PHONE_CALL exemption for active self-managed Telecom calls. Previous
     * placement inside the BroadcastReceiver hit BAL_BLOCK with
     * `callingUidProcState: BOUND_FOREGROUND_SERVICE; callingUidHasVisibleActivity: false`
     * (Galaxy logcat 2026-05-05 03:49:04.039).
     *
     * If this position still BAL_BLOCK's, the next escalation is to promote
     * `CallkitConnectionService` to a `foregroundServiceType="phoneCall"`
     * FGS for the lifetime of the connection.
     */
    fun markDeclined(context: Context) {
        recordHistory("ended", "declined")
        Log.d(TAG, "markDeclined id=$callId")
        // Do not launch the app on decline. End the self-managed Telecom call
        // immediately so declining from the notification/lock screen leaves a
        // backgrounded or terminated app closed (3.0.0 behavior).
        finishWithCause(DisconnectCause.REJECTED)
    }

    /**
     * Persist declined nonce and start MainActivity. Idempotent — safe even
     * if the BroadcastReceiver fallback also fires (single-task launch flag,
     * one-shot consumePending).
     */
    private fun triggerDeclineRecovery(context: Context) {
        try {
            val prefs = context.getSharedPreferences(
                "flutter_callkit_incoming_decline",
                Context.MODE_PRIVATE,
            )
            prefs.edit()
                .putString("pending_nonce", callId)
                .putLong("pending_at", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "[DIAG-DECLINE-CS] prefs written nonce=$callId")
            val launchIntent = AppUtils.getAppIntent(context, null, null)
            launchIntent?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Log.d(TAG, "[DIAG-DECLINE-CS] startActivity dispatched (CS ctx, RINGING)")
            } else {
                Log.w(TAG, "[DIAG-DECLINE-CS] launchIntent null — recovery skipped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DIAG-DECLINE-CS] recovery failed: ${e.message}")
        }
    }

    /** Mark the call as terminated — call ended (either side hung up). */
    fun markEnded(outcome: String? = null) {
        recordHistory("ended", outcome)
        Log.d(TAG, "markEnded id=$callId")
        finishWithCause(DisconnectCause.LOCAL)
    }

    /** Mark the call as missed — timeout without answer. */
    fun markMissed() {
        recordHistory("ended", "missed")
        Log.d(TAG, "markMissed id=$callId")
        finishWithCause(DisconnectCause.MISSED)
    }

    private fun finishWithCause(cause: Int) {
        try {
            setDisconnected(DisconnectCause(cause))
        } catch (e: Exception) {
            Log.w(TAG, "setDisconnected failed: ${e.message}")
        }
        unregister(callId, this)
        try {
            destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy failed: ${e.message}")
        }
    }

    private fun recordHistory(kind: String, outcome: String? = null) {
        PendingCallEvents.record(callId, historyScope, kind, historyDirection, historyRemote, outcome, historySession)
    }
}
