package com.hiennv.flutter_callkit_incoming

import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingCallEventsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var now = 1_000L
    private var nextId = 0
    private var nextNonce = 0

    private fun store(
        file: EventFile = TestEventFile(temporaryFolder.newFile()),
        keyByte: Byte = 7,
    ) = PendingCallEventsStore(
        file = file,
        key = SecretKeySpec(ByteArray(32) { keyByte }, "AES"),
        now = { now },
        newId = { "event-${++nextId}" },
        newNonce = { (++nextNonce).toByte().let { value -> ByteArray(12) { value } } },
    )

    @Test
    fun reconstructsStableEventsAndSuppressesDuplicateFacts() {
        val file = TestEventFile(temporaryFolder.newFile())
        val first = store(file)
        first.setScope("scope-a")
        val scope = first.scopeForStart()
        first.record("call-a", scope, "incoming", "inbound", "+12025550119")
        first.record("call-a", scope, "incoming", "inbound", "+12025550119")

        val original = first.pending("scope-a")
        val restored = store(file).pending("scope-a")

        assertEquals(1, original.size)
        assertEquals(original, restored)
    }

    @Test
    fun blankRemoteReplaysAsNull() {
        val file = TestEventFile(temporaryFolder.newFile())
        val first = store(file)
        first.setScope("scope-a")
        first.record("call-a", first.scopeForStart(), "incoming", "inbound", "   ")

        val event = first.pending("scope-a").single()
        assertEquals(null, event["remote"])
        assertEquals(event, store(file).pending("scope-a").single())
    }

    @Test
    fun callControlIdRemainsNullUntilCallControlProvidesOne() {
        val store = store()
        store.setScope("scope-a")
        store.record("call-a", store.scopeForStart(), "incoming", "inbound", "+12025550119")

        assertEquals(null, store.pending("scope-a").single()["android_call_control_id"])
    }

    @Test
    fun ackRemovesOnlyDeliveredIdsAndLeavesLaterFacts() {
        val store = store()
        store.setScope("scope-a")
        val scope = store.scopeForStart()
        store.record("call-a", scope, "started", "outbound", "+12025550119")
        val started = store.pending("scope-a").single()
        now++
        store.record("call-a", scope, "ended", "outbound", "+12025550119", "unknown")

        store.ack("scope-a", listOf(started.getValue("event_id") as String))

        val remaining = store.pending("scope-a")
        assertEquals(listOf("ended"), remaining.map { it["kind"] })
    }

    @Test
    fun changedFactGetsANewImmutableIdAndKeepsDeliveredFact() {
        val store = store()
        store.setScope("scope-a")
        val scope = store.scopeForStart()
        now = 2_000
        store.record("call-a", scope, "connected", "inbound", "+12025550119")
        val delivered = store.pending("scope-a").single()
        now = 1_500
        store.record("call-a", scope, "connected", "inbound", "+12025550119")

        val pending = store.pending("scope-a")
        assertEquals(2, pending.size)
        assertNotEquals(delivered["event_id"], pending.last()["event_id"])
        assertEquals("1970-01-01T00:00:01.500Z", pending.last()["at"])
    }

    @Test
    fun clearTombstonesOldScopeWithoutClearingNewScope() {
        val store = store()
        store.setScope("scope-a")
        val scopeA = store.scopeForStart()
        store.record("call-a", scopeA, "incoming", "inbound", "+12025550119")
        store.setScope("scope-b")
        val scopeB = store.scopeForStart()
        store.record("call-a", scopeB, "incoming", "inbound", "+12025550120")
        assertEquals(1, store.pending("scope-a").size)
        store.clear("scope-a")
        store.record("call-a", scopeA, "ended", "inbound", "+12025550119", "missed")

        assertEquals(1, store.pending("scope-b").size)
        assertHistoryFailure("history_unknown_generation") { store.pending("scope-a") }
    }

    @Test
    fun coldLoadedScopeStaysBoundToSessionAfterScopeChangeAndReusedCallId() {
        val file = TestEventFile(temporaryFolder.newFile())
        store(file).setScope("scope-a")
        val cold = store(file)
        cold.recordStart("call-shared", "session-a", null, "incoming", "inbound", "+12025550119")
        cold.setScope("scope-b")
        cold.recordStart("call-shared", "session-b", null, "incoming", "inbound", "+12025550120")

        val restarted = store(file)
        restarted.record(
            "call-shared",
            null,
            "ended",
            "inbound",
            "+12025550119",
            "missed",
            sessionKey = "session-a",
        )

        assertEquals(listOf("incoming", "ended"), restarted.pending("scope-a").map { it["kind"] })
        assertEquals(listOf("incoming"), restarted.pending("scope-b").map { it["kind"] })
    }

    @Test
    fun abortedTelecomOwnerPersistsOneFailedTerminal() {
        val store = store()
        store.setScope("scope-a")
        store.recordStart(
            "call-a",
            "session-a",
            store.scopeForStart(),
            "started",
            "outbound",
            "+12025550119",
        )
        lateinit var router: TelecomEventRouter
        router = TelecomEventRouter { action, outcome ->
            if (router.fromOwner(action, outcome)) {
                store.record(
                    "call-a",
                    "scope-a",
                    "ended",
                    "outbound",
                    "+12025550119",
                    outcome,
                    sessionKey = "session-a",
                )
            }
        }

        assertTrue(router.fromTelecom(CallkitConstants.ACTION_CALL_ENDED, "failed"))
        assertFalse(router.fromTelecom(CallkitConstants.ACTION_CALL_ENDED, "failed"))

        val terminal = store.pending("scope-a").single { it["kind"] == "ended" }
        assertEquals("failed", terminal["outcome"])
        assertEquals(1, store.pending("scope-a").count { it["kind"] == "ended" })
    }

    @Test
    fun rejectsCorruptCiphertextWrongKeyAndUnknownVersions() {
        val corruptFile = TestEventFile(temporaryFolder.newFile()).apply {
            write(byteArrayOf(1, 2, 3))
        }
        assertHistoryFailure("history_corrupt") { store(corruptFile) }

        val wrongKeyFile = TestEventFile(temporaryFolder.newFile())
        store(wrongKeyFile).setScope("scope-a")
        assertHistoryFailure("history_corrupt") { store(wrongKeyFile, keyByte = 8) }

        val unknownEnvelope = TestEventFile(temporaryFolder.newFile()).apply {
            write(PendingCallEventsCrypto.encrypt("{\"version\":2}".toByteArray(), SecretKeySpec(ByteArray(32) { 7 }, "AES"), ByteArray(12) { 4 }))
        }
        assertHistoryFailure("history_unknown_version") { store(unknownEnvelope) }

        val unknownEvent = TestEventFile(temporaryFolder.newFile()).apply {
            val json = """{"version":1,"current_scope":"scope-a","tombstones":[],"calls":[],"pending":[{"fact_class":"start","delivered":false,"event":{"version":2}}]}"""
            write(PendingCallEventsCrypto.encrypt(json.toByteArray(), SecretKeySpec(ByteArray(32) { 7 }, "AES"), ByteArray(12) { 4 }))
        }
        assertHistoryFailure("history_unknown_version") { store(unknownEvent) }
    }

    @Test
    fun rejectsVersionOneEventsWithUnexpectedPayloadFields() {
        val file = TestEventFile(temporaryFolder.newFile())
        val event = """{"version":1,"event_id":"e1","generation":"scope-a","call_key":"android:call-a","kind":"incoming","direction":"inbound","at":"1970-01-01T00:00:01.000Z","remote":"+12025550119","sdk_id":null,"native_id":"call-a","provider_leg_id":null,"provider_session_id":null,"android_call_control_id":null,"outcome":null,"secret":"must-not-replay"}"""
        val json = """{"version":1,"current_scope":"scope-a","tombstones":[],"calls":[{"generation":"scope-a","call_id":"call-a","direction":"inbound","remote":"+12025550119","facts":{"start":{"kind":"incoming","at":1000}}}],"pending":[{"fact_class":"start","delivered":false,"event":$event}]}"""
        file.write(PendingCallEventsCrypto.encrypt(json.toByteArray(), SecretKeySpec(ByteArray(32) { 7 }, "AES"), ByteArray(12) { 4 }))

        assertHistoryFailure("history_corrupt") { store(file) }
    }

    @Test
    fun failedAckLeavesExactIdsReplayable() {
        val file = FailingEventFile(TestEventFile(temporaryFolder.newFile()))
        val store = store(file)
        store.setScope("scope-a")
        store.record("call-a", store.scopeForStart(), "incoming", "inbound", "+12025550119")
        val event = store.pending("scope-a").single()
        file.failWrites = true

        assertHistoryFailure("history_unavailable") {
            store.ack("scope-a", listOf(event.getValue("event_id") as String))
        }
        file.failWrites = false
        assertEquals(listOf(event), store.pending("scope-a"))
    }

    @Test
    fun failedScopeWriteDoesNotActivateThatScope() {
        val file = FailingEventFile(TestEventFile(temporaryFolder.newFile())).apply {
            failWrites = true
        }
        val store = store(file)

        assertHistoryFailure("history_unavailable") { store.setScope("scope-a") }
        assertEquals(null, store.scopeForStart())
    }

    @Test
    fun usesAFreshNonceForEveryRewrite() {
        val file = TestEventFile(temporaryFolder.newFile())
        val store = store(file)
        store.setScope("scope-a")
        val first = file.read()!!.copyOfRange(1, 13)
        store.record("call-a", store.scopeForStart(), "incoming", "inbound", "+12025550119")
        val second = file.read()!!.copyOfRange(1, 13)

        assertFalse(first.contentEquals(second))
        assertArrayEquals(ByteArray(12) { 1 }, first)
        assertArrayEquals(ByteArray(12) { 2 }, second)
    }

    @Test
    fun boundsCallsAndDropsExpiredTombstones() {
        val store = store()
        store.setScope("scope-a")
        val scope = store.scopeForStart()
        repeat(501) { index ->
            now++
            store.record("call-$index", scope, "started", "outbound", "+12025550119")
        }
        assertEquals(500, store.pending("scope-a").map { it["call_key"] }.distinct().size)

        store.clear("scope-a")
        now += 7L * 24 * 60 * 60 * 1000 + 1
        store.setScope("scope-b")
        assertHistoryFailure("history_unknown_generation") { store.pending("scope-a") }
    }

    private fun assertHistoryFailure(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (error: HistoryStoreException) {
            assertEquals(code, error.code)
        }
    }
}

private class TestEventFile(private val file: File) : EventFile {
    override fun read(): ByteArray? = file.takeIf { it.length() > 0 }?.readBytes()
    override fun write(bytes: ByteArray) = file.writeBytes(bytes)
}

private class FailingEventFile(private val delegate: EventFile) : EventFile {
    var failWrites = false
    override fun read(): ByteArray? = delegate.read()
    override fun write(bytes: ByteArray) {
        if (failWrites) error("write failed")
        delegate.write(bytes)
    }
}
