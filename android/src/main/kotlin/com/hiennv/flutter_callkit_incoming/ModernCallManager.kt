package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.OutcomeReceiver
import android.telecom.CallAttributes
import android.telecom.CallControl
import android.telecom.CallControlCallback
import android.telecom.CallEventCallback
import android.telecom.CallException
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import java.util.concurrent.Executor
import java.util.function.Consumer

internal const val BAKLAVA_1_FULL_SDK = Build.VERSION_CODES_FULL.BAKLAVA_1

internal enum class TelecomPath { LEGACY, TRANSACTIONAL }

internal fun telecomPath(fullSdkInt: Int?): TelecomPath =
    if (supportsTransactionalTelecom(fullSdkInt)) TelecomPath.TRANSACTIONAL else TelecomPath.LEGACY

internal fun supportsTransactionalTelecom(fullSdkInt: Int?): Boolean =
    fullSdkInt != null && fullSdkInt >= BAKLAVA_1_FULL_SDK

internal fun currentFullSdkInt(): Int? {
    if (Build.VERSION.SDK_INT < 36) return null
    return try {
        Build.VERSION::class.java.getField("SDK_INT_FULL").getInt(null)
    } catch (_: Exception) {
        null
    }
}

internal fun usesTransactionalTelecom(): Boolean = telecomPath(currentFullSdkInt()) == TelecomPath.TRANSACTIONAL

internal data class ModernOwnerRoute(
    val accepted: Boolean,
    val originatedInTelecom: Boolean,
)

internal class ModernLifecycleRouter(sendToOwner: (String, String?) -> Unit) {
    private val router = TelecomEventRouter(sendToOwner)
    private val telecomOrigins = mutableSetOf<String>()

    @Synchronized
    fun fromTelecom(action: String, outcome: String? = null): Boolean {
        val key = eventKey(action)
        if (!telecomOrigins.add(key)) return false
        val accepted = router.fromTelecom(action, outcome)
        if (!accepted) telecomOrigins.remove(key)
        return accepted
    }

    @Synchronized
    fun fromOwner(action: String, outcome: String? = null): ModernOwnerRoute {
        val accepted = router.fromOwner(action, outcome)
        return ModernOwnerRoute(
            accepted,
            accepted && telecomOrigins.remove(eventKey(action)),
        )
    }

    private fun eventKey(action: String): String = when (action) {
        CallkitConstants.ACTION_CALL_DECLINE,
        CallkitConstants.ACTION_CALL_ENDED,
        CallkitConstants.ACTION_CALL_TIMEOUT -> "terminal"
        else -> action
    }
}

internal class ModernActivationRequests {
    private var sipConnected = false
    private var setActiveInFlight = false
    private var telecomActive = false
    private val completions = mutableListOf<(Boolean) -> Unit>()

    @Synchronized
    fun request(completion: (Boolean) -> Unit): Boolean {
        if (telecomActive) {
            completion(true)
            return false
        }
        completions += completion
        return claimSetActive()
    }

    @Synchronized
    fun sipConnected(): Boolean {
        sipConnected = true
        return claimSetActive()
    }

    @Synchronized
    fun complete(success: Boolean) {
        setActiveInFlight = false
        telecomActive = success
        val pending = completions.toList()
        completions.clear()
        pending.forEach { it(success) }
    }

    @Synchronized
    fun cancel() {
        sipConnected = false
        setActiveInFlight = false
        telecomActive = false
        val pending = completions.toList()
        completions.clear()
        pending.forEach { it(false) }
    }

    private fun claimSetActive(): Boolean {
        if (!sipConnected || telecomActive || setActiveInFlight) return false
        setActiveInFlight = true
        return true
    }
}

@SuppressLint("NewApi", "MissingPermission")
internal object ModernCallManager {
    private val ownership = CallOwnership()

    fun add(context: Context, data: Bundle): Boolean {
        if (!usesTransactionalTelecom()) return false
        val parsed = try {
            Data.fromBundle(data)
        } catch (_: Exception) {
            return false
        }
        val sessionKey = data.getString(PendingCallEvents.sessionExtra)
        if (parsed.id.isEmpty() || parsed.handle.isBlank() || sessionKey.isNullOrBlank()) return false

        val owner = ModernCallOwner(context.applicationContext, parsed.id, data)
        if (!ownership.activate(parsed.id, sessionKey, owner)) return false
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val direction = if (data.getString(PendingCallEvents.directionExtra) == "inbound") {
                CallAttributes.DIRECTION_INCOMING
            } else {
                CallAttributes.DIRECTION_OUTGOING
            }
            val attributes = CallAttributes.Builder(
                InAppCallManager(context.applicationContext).getPhoneAccountHandle(),
                direction,
                parsed.nameCaller.ifBlank { parsed.handle },
                Uri.fromParts("tel", parsed.handle, null),
            ).setCallType(CallAttributes.AUDIO_CALL)
                .setLogExcluded(false)
                .build()
            telecom.addCall(
                attributes,
                directExecutor,
                object : OutcomeReceiver<CallControl, CallException> {
                    override fun onResult(result: CallControl) {
                        var attached = false
                        ownership.dispatch(parsed.id, sessionKey) { active ->
                            if (active === owner) {
                                owner.attach(result)
                                attached = true
                            }
                        }
                        if (!attached) {
                            result.disconnect(DisconnectCause(DisconnectCause.LOCAL), directExecutor, unitOutcome)
                            return
                        }
                        PendingCallEvents.attachCallControl(
                            context.applicationContext,
                            sessionKey,
                            result.callId.uuid.toString(),
                        )
                    }

                    override fun onError(error: CallException) {
                        ownership.finish(parsed.id, owner)
                    }
                },
                owner,
                owner,
            )
            true
        } catch (_: Exception) {
            ownership.finish(parsed.id, owner)
            false
        }
    }

    fun drive(
        callId: String,
        sessionKey: String?,
        action: String,
        outcome: String? = null,
    ): Boolean? {
        if (!usesTransactionalTelecom()) return null
        if (ownership.owner(callId) == null) return null
        var applied = false
        val matched = ownership.dispatch(callId, sessionKey) { owner ->
            applied = (owner as ModernCallOwner).drive(action, outcome)
        }
        return matched && applied
    }

    private fun finish(callId: String, owner: ModernCallOwner) {
        ownership.finish(callId, owner)
    }

    private val directExecutor = Executor { command -> command.run() }

    private class ModernCallOwner(
        private val context: Context,
        private val callId: String,
        source: Bundle,
    ) : CallControlCallback, CallEventCallback {
        private val bundle = Bundle(source)
        private val lifecycle = ModernLifecycleRouter { action, outcome ->
            val eventBundle = Bundle(bundle)
            outcome?.let { eventBundle.putString(CallkitConnection.EXTRA_HISTORY_OUTCOME, it) }
            context.sendBroadcast(CallkitIncomingBroadcastReceiver.getIntent(context, action, eventBundle))
        }
        private val activation = ModernActivationRequests()
        private val pendingControlActions = mutableListOf<String>()
        @Volatile private var control: CallControl? = null

        @Synchronized
        fun attach(value: CallControl) {
            control = value
            pendingControlActions.toList().also { pendingControlActions.clear() }.forEach(::applyToControl)
        }

        fun drive(action: String, outcome: String?): Boolean {
            val route = lifecycle.fromOwner(action, outcome)
            if (!route.accepted) return false
            if (!route.originatedInTelecom) {
                if (control == null && eventKey(action) != TERMINAL) {
                    synchronized(this) {
                        if (control == null) pendingControlActions += action else applyToControl(action)
                    }
                } else {
                    applyToControl(action)
                }
            }
            if (eventKey(action) == TERMINAL) {
                activation.cancel()
                finish(callId, this)
            }
            return true
        }

        override fun onAnswer(videoState: Int, result: Consumer<Boolean>) {
            result.accept(lifecycle.fromTelecom(CallkitConstants.ACTION_CALL_ACCEPT))
        }

        override fun onDisconnect(cause: DisconnectCause, result: Consumer<Boolean>) {
            result.accept(lifecycle.fromTelecom(CallkitConstants.ACTION_CALL_ENDED))
        }

        override fun onSetActive(result: Consumer<Boolean>) {
            if (activation.request(result::accept)) setTelecomActive()
        }

        override fun onSetInactive(result: Consumer<Boolean>) = result.accept(true)

        override fun onCallStreamingStarted(result: Consumer<Boolean>) = result.accept(false)

        override fun onAvailableCallEndpointsChanged(endpoints: List<android.telecom.CallEndpoint>) = Unit

        override fun onCallEndpointChanged(endpoint: android.telecom.CallEndpoint) = Unit

        override fun onCallStreamingFailed(reason: Int) = Unit

        override fun onEvent(event: String, extras: Bundle) = Unit

        override fun onMuteStateChanged(isMuted: Boolean) = Unit

        private fun disconnect(cause: Int) {
            control?.disconnect(DisconnectCause(cause), directExecutor, unitOutcome)
        }

        private fun applyToControl(action: String) {
            when (action) {
                CallkitConstants.ACTION_CALL_ACCEPT -> control?.answer(VideoProfile.STATE_AUDIO_ONLY, directExecutor, unitOutcome)
                CallkitConstants.ACTION_CALL_CONNECTED -> if (activation.sipConnected()) setTelecomActive()
                CallkitConstants.ACTION_CALL_DECLINE -> disconnect(DisconnectCause.REJECTED)
                CallkitConstants.ACTION_CALL_TIMEOUT -> disconnect(DisconnectCause.MISSED)
                CallkitConstants.ACTION_CALL_ENDED -> disconnect(DisconnectCause.LOCAL)
            }
        }

        private fun setTelecomActive() {
            val activeControl = control
            if (activeControl == null) {
                activation.complete(false)
                return
            }
            activeControl.setActive(
                directExecutor,
                object : OutcomeReceiver<Void, CallException> {
                    override fun onResult(result: Void?) = activation.complete(true)
                    override fun onError(error: CallException) = activation.complete(false)
                },
            )
        }

        private fun eventKey(action: String): String = when (action) {
            CallkitConstants.ACTION_CALL_DECLINE,
            CallkitConstants.ACTION_CALL_ENDED,
            CallkitConstants.ACTION_CALL_TIMEOUT -> TERMINAL
            else -> action
        }
    }

    private val unitOutcome = object : OutcomeReceiver<Void, CallException> {
        override fun onResult(result: Void?) = Unit
        override fun onError(error: CallException) = Unit
    }

    private const val TERMINAL = "terminal"
}
