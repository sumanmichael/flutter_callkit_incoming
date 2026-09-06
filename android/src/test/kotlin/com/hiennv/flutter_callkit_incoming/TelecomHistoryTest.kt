package com.hiennv.flutter_callkit_incoming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TelecomHistoryTest {
    @Test
    fun historyExtraStartsAtApi28() {
        assertFalse(supportsSelfManagedCallHistory(24))
        assertFalse(supportsSelfManagedCallHistory(26))
        assertTrue(supportsSelfManagedCallHistory(28))
    }

    @Test
    fun duplicateOutgoingStartOwnsOneConnection() {
        val ownership = CallOwnership()
        val owner = Any()

        assertTrue(ownership.claimOutgoing("call-a"))
        assertFalse(ownership.claimOutgoing("call-a"))
        assertTrue(ownership.activate("call-a", "session-a", owner))
        assertFalse(ownership.claimOutgoing("call-a"))
        assertSame(owner, ownership.owner("call-a"))
        assertEquals(1, ownership.activeCount())
    }

    @Test
    fun lateOldEndCannotRemoveReplacementCall() {
        val ownership = CallOwnership()
        val old = Any()
        val replacement = Any()
        assertTrue(ownership.activate("call-a", "session-a", old))
        assertTrue(ownership.finish("call-a", old))
        assertTrue(ownership.activate("call-a", "session-b", replacement))

        assertFalse(ownership.finish("call-a", old))
        assertSame(replacement, ownership.owner("call-a"))
    }

    @Test
    fun delayedOldSessionEndDoesNotDispatchToReplacement() {
        val ownership = CallOwnership()
        val replacement = Any()
        var ended: Any? = null
        assertTrue(ownership.activate("call-a", "session-new", replacement))

        assertFalse(ownership.dispatch("call-a", "session-old") { ended = it })
        assertEquals(null, ended)
        assertTrue(ownership.dispatch("call-a", "session-new") { ended = it })
        assertSame(replacement, ended)
    }

    @Test
    fun activeCallRoundTripPreservesHistorySession() {
        val original = Data(
            mapOf(
                "id" to "call-a",
                "_callkitHistoryScope" to "scope-a",
                "_callkitHistorySession" to "session-a",
                "_callkitHistoryDirection" to "outbound",
            ),
        )

        val json = Utils.getGsonInstance().writeValueAsString(original)
        val restored = Utils.getGsonInstance().readValue(json, Data::class.java)

        assertEquals("scope-a", restored.historyScope)
        assertEquals("session-a", restored.historySession)
        assertEquals("outbound", restored.historyDirection)
    }

    @Test
    fun telecomCallbackRoutesOnceThroughOwnerFlow() {
        val sent = mutableListOf<String>()
        val router = TelecomEventRouter { sent += it }

        assertTrue(router.fromTelecom(CallkitConstants.ACTION_CALL_ACCEPT))
        assertFalse(router.fromTelecom(CallkitConstants.ACTION_CALL_ACCEPT))
        assertEquals(listOf(CallkitConstants.ACTION_CALL_ACCEPT), sent)
        assertTrue(router.fromOwner(CallkitConstants.ACTION_CALL_ACCEPT))
        assertFalse(router.fromOwner(CallkitConstants.ACTION_CALL_ACCEPT))

        assertTrue(router.fromTelecom(CallkitConstants.ACTION_CALL_ENDED))
        assertFalse(router.fromTelecom(CallkitConstants.ACTION_CALL_ENDED))
        assertEquals(
            listOf(CallkitConstants.ACTION_CALL_ACCEPT, CallkitConstants.ACTION_CALL_ENDED),
            sent,
        )
        assertTrue(router.fromOwner(CallkitConstants.ACTION_CALL_ENDED))
        assertFalse(router.fromOwner(CallkitConstants.ACTION_CALL_ENDED))
    }

    @Test
    fun ownerEventSuppressesLaterTelecomCallback() {
        val sent = mutableListOf<String>()
        val router = TelecomEventRouter { sent += it }

        assertTrue(router.fromOwner(CallkitConstants.ACTION_CALL_ENDED))
        assertFalse(router.fromTelecom(CallkitConstants.ACTION_CALL_ENDED))
        assertTrue(sent.isEmpty())
    }
}
