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
        assertTrue(ownership.activate("call-a", owner))
        assertFalse(ownership.claimOutgoing("call-a"))
        assertSame(owner, ownership.owner("call-a"))
        assertEquals(1, ownership.activeCount())
    }

    @Test
    fun lateOldEndCannotRemoveReplacementCall() {
        val ownership = CallOwnership()
        val old = Any()
        val replacement = Any()
        assertTrue(ownership.activate("call-a", old))
        assertTrue(ownership.finish("call-a", old))
        assertTrue(ownership.activate("call-a", replacement))

        assertFalse(ownership.finish("call-a", old))
        assertSame(replacement, ownership.owner("call-a"))
    }
}
