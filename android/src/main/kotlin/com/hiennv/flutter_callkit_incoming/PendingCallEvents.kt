package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class HistoryStoreException(val code: String) : RuntimeException(code)

internal fun runHistoryBestEffort(block: () -> Unit): Boolean = try {
    block()
    true
} catch (_: Exception) {
    false
}

internal class NativeHistoryAvailability {
    private var phoneAccountRegistrationFailed = false

    fun reportPhoneAccountRegistration(registered: Boolean) {
        phoneAccountRegistrationFailed = !registered
    }

    fun failureCode(storageFailureCode: String?): String? =
        storageFailureCode ?: if (phoneAccountRegistrationFailed) "history_unavailable" else null
}

internal interface EventFile {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

private class AtomicEventFile(file: File) : EventFile {
    private val file = AtomicFile(file)

    override fun read(): ByteArray? =
        if (!file.baseFile.exists()) null else file.openRead().use { it.readBytes() }

    override fun write(bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}

internal object PendingCallEventsCrypto {
    private val aad = "vspphone-history-1".toByteArray(Charsets.US_ASCII)

    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv
        require(nonce.size == 12)
        cipher.updateAAD(aad)
        return byteArrayOf(1) + nonce + cipher.doFinal(plain)
    }

    fun decrypt(bytes: ByteArray, key: SecretKey): ByteArray {
        if (bytes.size < 30 || bytes[0].toInt() != 1) throw HistoryStoreException("history_corrupt")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        } catch (error: HistoryStoreException) {
            throw error
        } catch (error: Exception) {
            throw HistoryStoreException("history_corrupt")
        }
    }
}

private data class StoredFact(val kind: String, val at: Long, val outcome: String?)
private data class StoredCall(
    val generation: String,
    val callId: String,
    val sessionKey: String,
    val direction: String,
    val remote: String?,
    var androidCallControlId: String? = null,
    val facts: MutableMap<String, StoredFact> = linkedMapOf(),
)
private data class PendingEvent(
    val factClass: String,
    var delivered: Boolean,
    var value: Map<String, Any?>,
)

internal data class PendingCallbackRequest(
    val requestId: String,
    val generation: String,
    val sourceKey: String,
    val destination: String,
    val createdAt: Long,
    var delivered: Boolean = false,
) {
    fun value(): Map<String, Any?> = linkedMapOf(
        "version" to 1,
        "request_id" to requestId,
        "generation" to generation,
        "destination" to destination,
        "created_at" to createdAt,
    )
}

internal enum class CallbackRequestStatus {
    READY,
    DUPLICATE,
    CAPACITY,
    MISSING,
    UNKNOWN,
    EXPIRED,
    STALE_SCOPE,
    INVALID_DESTINATION,
}

internal data class CallbackRequestResult(
    val status: CallbackRequestStatus,
    val request: PendingCallbackRequest? = null,
)

internal data class CallbackCall(
    val generation: String,
    val callId: String,
    val direction: String,
    val remote: String?,
)

internal data class CallbackResolution(
    val status: Status,
    val call: CallbackCall? = null,
) {
    enum class Status { READY, MISSING, UNKNOWN, EXPIRED, STALE_SCOPE }
}

internal class PendingCallEventsStore(
    private val file: EventFile,
    private val key: SecretKey,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mapper = ObjectMapper()
    @Volatile private var currentScope: String? = null
    private val tombstones = linkedMapOf<String, Long>()
    private val calls = linkedMapOf<String, StoredCall>()
    private val events = mutableListOf<PendingEvent>()
    private val callbackRequests = mutableListOf<PendingCallbackRequest>()

    init {
        file.read()?.let { load(it) }
    }

    fun setScope(generation: String) {
        validateGeneration(generation)
        prune()
        if (tombstones.containsKey(generation)) throw HistoryStoreException("history_unknown_generation")
        val oldScope = currentScope
        currentScope = generation
        try {
            persist()
        } catch (error: Exception) {
            currentScope = oldScope
            throw error
        }
    }

    fun scopeForStart(): String? = currentScope?.takeUnless(tombstones::containsKey)

    fun recordStart(
        callId: String,
        sessionKey: String,
        originalScope: String?,
        kind: String,
        direction: String,
        remote: String,
        at: Long = now(),
    ) {
        record(
            callId,
            originalScope ?: scopeForStart(),
            kind,
            direction,
            remote,
            at = at,
            sessionKey = sessionKey,
        )
    }

    fun record(
        callId: String,
        originalScope: String?,
        kind: String,
        direction: String,
        remote: String,
        outcome: String? = null,
        at: Long = now(),
        sessionKey: String? = null,
    ) {
        if (callId.isEmpty() || kind !in kinds || direction !in directions || outcome !in outcomes) return
        val existing = when {
            sessionKey != null -> calls.values.singleOrNull { it.sessionKey == sessionKey }
            originalScope != null -> calls[ledgerKey(originalScope, callId)]
            else -> calls.values.filter { it.callId == callId }.singleOrNull()
        }
        val generation = originalScope ?: existing?.generation ?: return
        if (tombstones.containsKey(generation)) return
        if (existing != null && existing.generation != generation) return
        val call = existing ?: StoredCall(
            generation,
            callId,
            sessionKey ?: "legacy:$generation:$callId",
            direction,
            remote.takeIf { it.isNotBlank() },
        ).also {
            calls[ledgerKey(generation, callId)] = it
        }
        val factClass = factClass(kind)
        val old = call.facts[factClass]
        if (old != null) {
            if (factClass == "terminal" || at >= old.at) return
        }
        val resolvedOutcome = if (kind == "ended" && outcome == null) {
            if (call.facts.containsKey("connected")) "answered" else "unknown"
        } else outcome
        call.facts[factClass] = StoredFact(kind, at, resolvedOutcome)
        val oldPending = events.filter {
            it.value["generation"] == generation &&
                it.value["call_key"] == callKey(callId) &&
                it.factClass == factClass
        }
        events.removeAll(oldPending.filterNot { it.delivered }.toSet())
        events += PendingEvent(factClass, false, event(call, kind, at, resolvedOutcome))
        prune()
        persist()
    }

    fun attachCallControl(sessionKey: String, callControlId: String): Boolean {
        val normalized = normalizedUuid(callControlId) ?: return false
        val call = calls.values.singleOrNull { it.sessionKey == sessionKey } ?: return false
        if (calls.values.any { it !== call && it.androidCallControlId == normalized }) return false
        if (call.androidCallControlId != null && call.androidCallControlId != normalized) return false
        if (call.androidCallControlId == normalized) return true
        val existingEvents = events.toList()
        val oldValues = existingEvents.map { it to it.value }
        call.androidCallControlId = normalized
        val matching = events.filter {
            it.value["generation"] == call.generation && it.value["call_key"] == callKey(call.callId)
        }
        matching.filterNot { it.delivered }.forEach { pending ->
            pending.value = LinkedHashMap(pending.value).apply {
                put("android_call_control_id", normalized)
            }
        }
        if (matching.isNotEmpty() && matching.all { it.delivered }) {
            val latest = call.facts.values.maxByOrNull { it.at }
            if (latest != null) {
                events += PendingEvent(
                    factClass(latest.kind),
                    false,
                    event(call, latest.kind, latest.at, latest.outcome),
                )
            }
        }
        try {
            persist()
        } catch (error: Exception) {
            call.androidCallControlId = null
            events.removeAll { candidate -> existingEvents.none { it === candidate } }
            oldValues.forEach { (pending, value) -> pending.value = value }
            throw error
        }
        return true
    }

    fun resolveCallback(callControlId: String?): CallbackResolution {
        val normalized = callControlId?.let(::normalizedUuid)
            ?: return CallbackResolution(CallbackResolution.Status.MISSING)
        val call = calls.values.singleOrNull { it.androidCallControlId == normalized }
            ?: return CallbackResolution(CallbackResolution.Status.UNKNOWN)
        val lastFactAt = call.facts.values.maxOfOrNull { it.at } ?: return CallbackResolution(CallbackResolution.Status.UNKNOWN)
        val details = CallbackCall(call.generation, call.callId, call.direction, call.remote)
        if (lastFactAt < now() - retentionMillis) return CallbackResolution(CallbackResolution.Status.EXPIRED, details)
        if (call.generation != currentScope) return CallbackResolution(CallbackResolution.Status.STALE_SCOPE, details)
        return CallbackResolution(CallbackResolution.Status.READY, details)
    }

    fun enqueueModernCallback(callControlId: String?): CallbackRequestResult {
        val resolution = resolveCallback(callControlId)
        val status = when (resolution.status) {
            CallbackResolution.Status.READY -> null
            CallbackResolution.Status.MISSING -> CallbackRequestStatus.MISSING
            CallbackResolution.Status.UNKNOWN -> CallbackRequestStatus.UNKNOWN
            CallbackResolution.Status.EXPIRED -> CallbackRequestStatus.EXPIRED
            CallbackResolution.Status.STALE_SCOPE -> CallbackRequestStatus.STALE_SCOPE
        }
        if (status != null) return CallbackRequestResult(status)
        val call = requireNotNull(resolution.call)
        val destination = callbackDestination(call.remote)
            ?: return CallbackRequestResult(CallbackRequestStatus.INVALID_DESTINATION)
        return enqueueCallback(call.generation, "modern:${normalizedUuid(callControlId!!)!!}", destination)
    }

    fun enqueueLegacyCallback(uri: String?): CallbackRequestResult {
        val generation = scopeForStart()
            ?: return CallbackRequestResult(CallbackRequestStatus.STALE_SCOPE)
        val destination = legacyCallbackDestination(uri)
            ?: return CallbackRequestResult(if (uri == null) CallbackRequestStatus.MISSING else CallbackRequestStatus.INVALID_DESTINATION)
        return enqueueCallback(generation, "legacy:$destination", destination)
    }

    fun pendingCallbacks(generation: String): List<Map<String, Any?>> {
        requireKnownGeneration(generation)
        return pendingCallbacksFor(generation)
    }

    fun pendingCurrentScopeCallbacks(): List<Map<String, Any?>> {
        val generation = currentScope ?: return emptyList()
        return pendingCallbacksFor(generation)
    }

    private fun pendingCallbacksFor(generation: String): List<Map<String, Any?>> {
        prune()
        val selected = callbackRequests.firstOrNull { it.generation == generation } ?: return emptyList()
        if (!selected.delivered) {
            selected.delivered = true
            try {
                persist()
            } catch (error: Exception) {
                selected.delivered = false
                throw error
            }
        }
        return listOf(selected.value())
    }

    fun ackCallbacks(generation: String, requestIds: List<String>) {
        requireKnownGeneration(generation)
        val removed = callbackRequests.filter {
            it.generation == generation && it.delivered && it.requestId in requestIds
        }
        callbackRequests.removeAll(removed.toSet())
        try {
            persist()
        } catch (error: Exception) {
            callbackRequests.addAll(removed)
            callbackRequests.sortBy { it.createdAt }
            throw error
        }
    }

    private fun enqueueCallback(generation: String, sourceKey: String, destination: String): CallbackRequestResult {
        prune()
        callbackRequests.singleOrNull { it.generation == generation && it.sourceKey == sourceKey }?.let {
            return CallbackRequestResult(CallbackRequestStatus.DUPLICATE, it)
        }
        if (callbackRequests.size >= maxCallbackRequests) {
            return CallbackRequestResult(CallbackRequestStatus.CAPACITY)
        }
        val oldRequests = callbackRequests.toList()
        val request = PendingCallbackRequest(newId(), generation, sourceKey, destination, now())
        callbackRequests += request
        try {
            persist()
        } catch (error: Exception) {
            callbackRequests.clear()
            callbackRequests.addAll(oldRequests)
            throw error
        }
        return CallbackRequestResult(CallbackRequestStatus.READY, request)
    }

    fun pending(generation: String): List<Map<String, Any?>> {
        requireKnownGeneration(generation)
        val selected = events.filter { it.value["generation"] == generation }
        val newlyDelivered = selected.filterNot { it.delivered }
        if (newlyDelivered.isNotEmpty()) {
            newlyDelivered.forEach { it.delivered = true }
            try {
                persist()
            } catch (error: Exception) {
                newlyDelivered.forEach { it.delivered = false }
                throw error
            }
        }
        return selected.map { LinkedHashMap(it.value) }
    }

    fun ack(generation: String, eventIds: List<String>) {
        requireKnownGeneration(generation)
        val removed = events.filter {
            it.value["generation"] == generation && it.value["event_id"] in eventIds
        }
        events.removeAll(removed.toSet())
        try {
            persist()
        } catch (error: Exception) {
            events.addAll(removed)
            throw error
        }
    }

    fun clear(generation: String) {
        validateGeneration(generation)
        val oldScope = currentScope
        val oldTombstone = tombstones[generation]
        val oldCalls = calls.filterValues { it.generation == generation }
        val oldEvents = events.filter { it.value["generation"] == generation }
        val oldCallbacks = callbackRequests.filter { it.generation == generation }
        tombstones[generation] = now()
        if (currentScope == generation) currentScope = null
        calls.entries.removeAll { it.value.generation == generation }
        events.removeAll(oldEvents.toSet())
        callbackRequests.removeAll(oldCallbacks.toSet())
        try {
            persist()
        } catch (error: Exception) {
            currentScope = oldScope
            if (oldTombstone == null) tombstones.remove(generation) else tombstones[generation] = oldTombstone
            calls.putAll(oldCalls)
            events.addAll(oldEvents)
            callbackRequests.addAll(oldCallbacks)
            throw error
        }
    }

    private fun event(call: StoredCall, kind: String, at: Long, outcome: String?): Map<String, Any?> = linkedMapOf(
        "version" to 1,
        "event_id" to newId(),
        "generation" to call.generation,
        "call_key" to callKey(call.callId),
        "kind" to kind,
        "direction" to call.direction,
        "at" to formatTime(at),
        "remote" to call.remote,
        "sdk_id" to null,
        "native_id" to call.callId,
        "provider_leg_id" to null,
        "provider_session_id" to null,
        "android_call_control_id" to call.androidCallControlId,
        "outcome" to outcome,
    )

    private fun persist() {
        val root = mapper.createObjectNode().put("version", 1)
        currentScope?.let { root.put("current_scope", it) }
        val tombstoneArray = root.putArray("tombstones")
        tombstones.forEach { (generation, clearedAt) ->
            tombstoneArray.addObject().put("generation", generation).put("cleared_at", clearedAt)
        }
        val callArray = root.putArray("calls")
        calls.values.forEach { call ->
            val node = callArray.addObject()
                .put("generation", call.generation)
                .put("call_id", call.callId)
                .put("session_key", call.sessionKey)
                .put("direction", call.direction)
                .put("remote", call.remote)
                .put("android_call_control_id", call.androidCallControlId)
            val facts = node.putObject("facts")
            call.facts.forEach { (name, fact) ->
                val factNode = facts.putObject(name).put("kind", fact.kind).put("at", fact.at)
                fact.outcome?.let { factNode.put("outcome", it) }
            }
        }
        val pendingArray = root.putArray("pending")
        events.forEach { pending ->
            val node = pendingArray.addObject()
                .put("fact_class", pending.factClass)
                .put("delivered", pending.delivered)
            node.set<JsonNode>("event", mapper.valueToTree(pending.value))
        }
        val callbackArray = root.putArray("callback_requests")
        callbackRequests.forEach { request ->
            callbackArray.addObject()
                .put("request_id", request.requestId)
                .put("generation", request.generation)
                .put("source_key", request.sourceKey)
                .put("destination", request.destination)
                .put("created_at", request.createdAt)
                .put("delivered", request.delivered)
        }
        try {
            file.write(PendingCallEventsCrypto.encrypt(mapper.writeValueAsBytes(root), key))
        } catch (error: HistoryStoreException) {
            throw error
        } catch (_: Exception) {
            throw HistoryStoreException("history_unavailable")
        }
    }

    private fun load(bytes: ByteArray) {
        val root = try {
            mapper.readTree(PendingCallEventsCrypto.decrypt(bytes, key))
        } catch (error: HistoryStoreException) {
            throw error
        } catch (error: Exception) {
            throw HistoryStoreException("history_corrupt")
        }
        if (root.path("version").asInt(-1) != 1) throw HistoryStoreException("history_unknown_version")
        currentScope = root.get("current_scope")?.takeUnless(JsonNode::isNull)?.asText()
        try {
            root.path("tombstones").forEach { tombstones[it.required("generation").asText()] = it.required("cleared_at").asLong() }
            root.path("calls").forEach { node ->
                val call = StoredCall(
                    node.required("generation").asText(),
                    node.required("call_id").asText(),
                    node.get("session_key")?.takeUnless(JsonNode::isNull)?.asText()
                        ?: "legacy:${node.required("generation").asText()}:${node.required("call_id").asText()}",
                    node.required("direction").asText(),
                    node.required("remote").takeUnless(JsonNode::isNull)?.asText(),
                    node.get("android_call_control_id")?.takeUnless(JsonNode::isNull)?.asText(),
                )
                if (call.androidCallControlId != null && normalizedUuid(call.androidCallControlId!!) == null) {
                    throw HistoryStoreException("history_corrupt")
                }
                node.required("facts").fields().forEach { (name, fact) ->
                    call.facts[name] = StoredFact(
                        fact.required("kind").asText(),
                        fact.required("at").asLong(),
                        fact.get("outcome")?.takeUnless(JsonNode::isNull)?.asText(),
                    )
                }
                calls[ledgerKey(call.generation, call.callId)] = call
            }
            root.path("pending").forEach { node ->
                val event = node.required("event")
                if (event.path("version").asInt(-1) != 1) throw HistoryStoreException("history_unknown_version")
                validateEvent(event)
                @Suppress("UNCHECKED_CAST")
                val value = mapper.convertValue(event, LinkedHashMap::class.java) as Map<String, Any?>
                val call = calls[ledgerKey(value["generation"] as String, value["native_id"] as String)]
                    ?: throw HistoryStoreException("history_corrupt")
                if (value["call_key"] != callKey(call.callId)) throw HistoryStoreException("history_corrupt")
                val eventCallControlId = value["android_call_control_id"] as String?
                if (eventCallControlId != null && eventCallControlId != call.androidCallControlId) {
                    throw HistoryStoreException("history_corrupt")
                }
                events += PendingEvent(node.required("fact_class").asText(), node.path("delivered").asBoolean(false), value)
            }
            root.path("callback_requests").forEach { node ->
                val request = PendingCallbackRequest(
                    node.required("request_id").asText(),
                    node.required("generation").asText(),
                    node.required("source_key").asText(),
                    node.required("destination").asText(),
                    node.required("created_at").asLong(),
                    node.path("delivered").asBoolean(false),
                )
                if (request.requestId.isBlank() || request.generation.isBlank() ||
                    request.sourceKey.isBlank() || request.destination.isBlank()
                ) throw HistoryStoreException("history_corrupt")
                callbackRequests += request
            }
            val callControlIds = calls.values.mapNotNull { it.androidCallControlId }
            if (callControlIds.size != callControlIds.distinct().size) throw HistoryStoreException("history_corrupt")
            val requestIds = callbackRequests.map { it.requestId }
            if (requestIds.size != requestIds.distinct().size) throw HistoryStoreException("history_corrupt")
        } catch (error: HistoryStoreException) {
            throw error
        } catch (error: Exception) {
            throw HistoryStoreException("history_corrupt")
        }
        prune()
    }

    private fun prune() {
        val cutoff = now() - retentionMillis
        tombstones.entries.removeAll { it.value < cutoff }
        while (tombstones.size > maxCalls) tombstones.remove(tombstones.entries.first().key)
        val expiredCalls = calls.values.filter { call -> call.facts.values.maxOfOrNull { it.at }?.let { it < cutoff } ?: true }
        expiredCalls.forEach { calls.remove(ledgerKey(it.generation, it.callId)) }
        while (calls.size > maxCalls) calls.remove(calls.entries.first().key)
        val retained = calls.values.map { it.generation to callKey(it.callId) }.toSet()
        events.removeAll { (it.value["generation"] to it.value["call_key"]) !in retained }
        while (events.size > maxEvents) events.removeAt(0)
        callbackRequests.removeAll {
            !it.delivered &&
                (it.createdAt < now() - callbackRequestRetentionMillis || tombstones.containsKey(it.generation))
        }
    }

    private fun requireKnownGeneration(generation: String) {
        validateGeneration(generation)
        if (tombstones.containsKey(generation) ||
            generation != currentScope && calls.values.none { it.generation == generation } &&
                events.none { it.value["generation"] == generation } &&
                callbackRequests.none { it.generation == generation }
        ) throw HistoryStoreException("history_unknown_generation")
    }

    private fun validateGeneration(generation: String) {
        if (generation.isBlank()) throw HistoryStoreException("history_invalid_arguments")
    }

    private fun validateEvent(event: JsonNode) {
        val names = event.fieldNames().asSequence().toSet()
        if (names != eventFields ||
            !event.path("version").isInt ||
            requiredEventStrings.any { !event.path(it).isTextual } ||
            event.path("kind").asText() !in kinds ||
            event.path("direction").asText() !in directions ||
            nullableEventStrings.any { !event.path(it).isNull && !event.path(it).isTextual } ||
            (!event.path("outcome").isNull && event.path("outcome").asText() !in outcomes)
        ) throw HistoryStoreException("history_corrupt")
    }

    private fun formatTime(milliseconds: Long): String = requireNotNull(timeFormat.get()).format(Date(milliseconds))

    companion object {
        private const val maxCalls = 500
        private const val maxEvents = maxCalls * 5
        private const val maxCallbackRequests = 8
        private const val callbackRequestRetentionMillis = 10L * 60 * 1000
        private const val retentionMillis = 7L * 24 * 60 * 60 * 1000
        private val kinds = setOf("started", "incoming", "accepted", "connected", "ended")
        private val directions = setOf("inbound", "outbound")
        private val outcomes = setOf(null, "answered", "declined", "missed", "failed", "interrupted", "unknown")
        private fun callKey(callId: String) = "android:$callId"
        private fun factClass(kind: String) = if (kind == "started" || kind == "incoming") "start" else if (kind == "ended") "terminal" else kind
        private fun ledgerKey(generation: String, callId: String) = "$generation\u0000$callId"
        private val requiredEventStrings = setOf("event_id", "generation", "call_key", "kind", "direction", "at", "native_id")
        private val nullableEventStrings = setOf("remote", "sdk_id", "provider_leg_id", "provider_session_id", "android_call_control_id", "outcome")
        private val eventFields = requiredEventStrings + nullableEventStrings + "version"
        private fun normalizedUuid(value: String): String? = try {
            UUID.fromString(value).toString()
        } catch (_: Exception) {
            null
        }
        private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }
}

internal fun legacyCallbackDestination(value: String?): String? {
    val raw = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    val uri = try {
        URI(raw)
    } catch (_: Exception) {
        return null
    }
    if (uri.rawQuery != null || uri.rawFragment != null) return null
    val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
    val body = uri.rawSchemeSpecificPart ?: return null
    return when (scheme) {
        "tel" -> body.takeIf { it.matches(Regex("\\+?[0-9]{2,32}")) }
        "sip" -> body.takeIf {
            it.matches(Regex("[A-Za-z0-9_.!~*'()+-]{1,128}@[A-Za-z0-9.-]{1,253}")) &&
                it.substringAfter('@').contains('.')
        }?.let { "sip:$it" }
        else -> null
    }
}

private fun callbackDestination(value: String?): String? {
    val raw = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return if (raw.startsWith("sip:", ignoreCase = true)) {
        legacyCallbackDestination(raw)
    } else {
        legacyCallbackDestination("tel:$raw")
    }
}

internal object PendingCallEvents {
    const val scopeExtra = "com.hiennv.flutter_callkit_incoming.HISTORY_SCOPE"
    const val directionExtra = "com.hiennv.flutter_callkit_incoming.HISTORY_DIRECTION"
    const val sessionExtra = "com.hiennv.flutter_callkit_incoming.HISTORY_SESSION"
    private val executor = Executors.newSingleThreadExecutor()
    private val availability = NativeHistoryAvailability()
    @Volatile private var store: PendingCallEventsStore? = null
    @Volatile private var failureCode: String? = null

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        executor.execute { initializeNow(applicationContext) }
    }

    fun reportPhoneAccountRegistration(registered: Boolean) {
        executor.execute { availability.reportPhoneAccountRegistration(registered) }
    }

    private fun initializeNow(context: Context) {
        if (store != null || failureCode != null) return
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) throw HistoryStoreException("history_unavailable")
            store = PendingCallEventsStore(
                AtomicEventFile(File(context.noBackupFilesDir, "pending_call_events")),
                historyKey(),
            )
        } catch (error: HistoryStoreException) {
            failureCode = error.code
        } catch (_: Exception) {
            failureCode = "history_unavailable"
        }
    }

    fun scopeForStart(): String? = store?.scopeForStart()

    fun newSessionKey(): String = UUID.randomUUID().toString()

    fun record(callId: String, scope: String?, kind: String, direction: String, remote: String, outcome: String? = null, sessionKey: String? = null) {
        record(null, callId, scope, kind, direction, remote, outcome, sessionKey)
    }

    fun recordStart(context: Context, callId: String, sessionKey: String, scope: String?, kind: String, direction: String, remote: String) {
        val observedAt = System.currentTimeMillis()
        val applicationContext = context.applicationContext
        executor.execute {
            runHistoryBestEffort {
                initializeNow(applicationContext)
                store?.let { active ->
                    active.recordStart(callId, sessionKey, scope, kind, direction, remote, observedAt)
                }
            }
        }
    }

    fun record(context: Context?, callId: String, scope: String?, kind: String, direction: String, remote: String, outcome: String? = null, sessionKey: String? = null) {
        val observedAt = System.currentTimeMillis()
        executor.execute {
            runHistoryBestEffort {
                if (store == null && context != null) initializeNow(context.applicationContext)
                store?.record(callId, scope, kind, direction, remote, outcome, observedAt, sessionKey)
            }
        }
    }

    fun attachCallControl(context: Context, sessionKey: String, callControlId: String) {
        val applicationContext = context.applicationContext
        executor.execute {
            runHistoryBestEffort {
                initializeNow(applicationContext)
                store?.attachCallControl(sessionKey, callControlId)
            }
        }
    }

    fun enqueueModernCallback(
        context: Context,
        callControlId: String?,
        completion: (String) -> Unit,
    ) {
        enqueueCallback(context, completion) { it.enqueueModernCallback(callControlId) }
    }

    fun enqueueLegacyCallback(
        context: Context,
        uri: String?,
        completion: (String) -> Unit,
    ) {
        enqueueCallback(context, completion) { it.enqueueLegacyCallback(uri) }
    }

    private fun enqueueCallback(
        context: Context,
        completion: (String) -> Unit,
        create: (PendingCallEventsStore) -> CallbackRequestResult,
    ) {
        val applicationContext = context.applicationContext
        executor.execute {
            val result = try {
                initializeNow(applicationContext)
                val active = store ?: throw HistoryStoreException(failureCode ?: "history_unavailable")
                create(active)
            } catch (_: Exception) {
                CallbackRequestResult(CallbackRequestStatus.UNKNOWN)
            }
            if (result.status == CallbackRequestStatus.READY) {
                FlutterCallkitIncomingPlugin.notifyPendingCallback()
            }
            reply { completion(result.status.name.lowercase(Locale.US)) }
        }
    }

    fun handle(call: MethodCall, result: MethodChannel.Result) {
        executor.execute {
            try {
                availability.failureCode(failureCode)?.let { throw HistoryStoreException(it) }
                val active = store ?: throw HistoryStoreException("history_unavailable")
                val value: Any? = if (call.method == "pendingCurrentScopeCallbacks") {
                    active.pendingCurrentScopeCallbacks()
                } else {
                    val args = call.arguments as? Map<*, *> ?: throw HistoryStoreException("history_invalid_arguments")
                    val generation = args["generation"] as? String ?: throw HistoryStoreException("history_invalid_arguments")
                    when (call.method) {
                    "setScope" -> {
                        active.setScope(generation)
                        null
                    }
                    "pending" -> active.pending(generation)
                    "pendingCallbacks" -> active.pendingCallbacks(generation)
                    "ack" -> {
                        val ids = (args["event_ids"] as? List<*>)?.map {
                            it as? String ?: throw HistoryStoreException("history_invalid_arguments")
                        } ?: throw HistoryStoreException("history_invalid_arguments")
                        active.ack(generation, ids)
                        null
                    }
                    "ackCallbacks" -> {
                        val ids = (args["request_ids"] as? List<*>)?.map {
                            it as? String ?: throw HistoryStoreException("history_invalid_arguments")
                        } ?: throw HistoryStoreException("history_invalid_arguments")
                        active.ackCallbacks(generation, ids)
                        null
                    }
                    "clear" -> {
                        active.clear(generation)
                        null
                    }
                    else -> throw HistoryStoreException("history_invalid_arguments")
                    }
                }
                reply { result.success(value) }
            } catch (error: HistoryStoreException) {
                reply { result.error(error.code, error.code, null) }
            } catch (_: Exception) {
                reply { result.error("history_unavailable", "history_unavailable", null) }
            }
        }
    }

    private fun reply(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

    private fun historyKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey("vspphone_pending_history", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                "vspphone_pending_history",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}

object AndroidCallbackHandoff {
    const val ACTION_CALL_BACK = "android.telecom.action.CALL_BACK"
    const val ACTION_HANDOFF = "com.hiennv.flutter_callkit_incoming.CALLBACK_HANDOFF"
    const val EXTRA_UUID = "android.telecom.extra.UUID"
    const val EXTRA_RESULT = "com.hiennv.flutter_callkit_incoming.CALLBACK_RESULT"

    @JvmStatic
    fun handleModern(
        context: Context,
        action: String?,
        callControlId: String?,
        completion: (String) -> Unit,
    ) {
        if (!acceptsModernCallback(action, currentFullSdkInt())) {
            completion("unsupported")
            return
        }
        PendingCallEvents.enqueueModernCallback(context, callControlId, completion)
    }

    internal fun handleLegacy(
        context: Context,
        uri: String?,
        completion: (String) -> Unit,
    ) {
        if (usesTransactionalTelecom()) {
            completion("unsupported")
            return
        }
        PendingCallEvents.enqueueLegacyCallback(context, uri, completion)
    }
}

internal fun acceptsModernCallback(action: String?, fullSdkInt: Int?): Boolean =
    action == AndroidCallbackHandoff.ACTION_CALL_BACK && supportsTransactionalTelecom(fullSdkInt)
