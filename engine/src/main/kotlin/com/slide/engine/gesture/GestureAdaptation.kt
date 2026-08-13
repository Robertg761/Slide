package com.slide.engine.gesture

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/** A versioned, text-free snapshot of the person's swipe corrections. */
data class GestureAdaptationSnapshot(
    val version: Int,
    val saltHex: String,
    val epoch: Long,
    val alternatives: List<GestureAlternativePreference>,
    val rejections: List<GestureRejectionPreference>,
)

/** A preference for [chosenFingerprint] when it competes with [rejectedFingerprint]. */
data class GestureAlternativePreference(
    val rejectedFingerprint: Long,
    val chosenFingerprint: Long,
    val strength: Int,
    val lastEpoch: Long,
)

/** Bounded evidence that a decoded word was immediately undone. */
data class GestureRejectionPreference(
    val fingerprint: Long,
    val strength: Int,
    val lastEpoch: Long,
)

/**
 * Learns only from explicit swipe outcomes and re-ranks either decoder's candidate list.
 *
 * Scores from the neural and deterministic decoders live on different scales. Adaptation therefore
 * works in rank space: the decoder's order is the baseline, then bounded personal evidence can move
 * a candidate by a small number of places. When no learned preference applies, the original list
 * and its scores are returned byte-for-byte unchanged.
 *
 * Persisted entries contain salted fingerprints, strengths, and logical ages. They contain no
 * swipe trace, touch coordinate, surrounding text, app/editor identity, or timestamp. A salted
 * fingerprint is not encryption against someone who already owns the private app storage, but it
 * prevents the adaptation file itself from being a readable history of words.
 */
class GestureAdaptation internal constructor(
    initialSalt: ByteArray,
    private val alternativeCapacity: Int,
    private val rejectionCapacity: Int,
    private val decayInterval: Long,
) {
    constructor() : this(
        initialSalt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes),
        alternativeCapacity = DEFAULT_ALTERNATIVE_CAPACITY,
        rejectionCapacity = DEFAULT_REJECTION_CAPACITY,
        decayInterval = DEFAULT_DECAY_INTERVAL,
    )

    private data class PairKey(val rejected: Long, val chosen: Long)
    private data class Evidence(var strength: Int, var lastEpoch: Long)
    private data class Ranked(
        val originalIndex: Int,
        val candidate: GestureCandidate,
        val fingerprint: Long?,
        var adjustment: Float = 0f,
    )

    private var salt = initialSalt.copyOf()
    private var epoch = 0L
    private val alternatives = LinkedHashMap<PairKey, Evidence>()
    private val rejections = LinkedHashMap<Long, Evidence>()

    init {
        require(salt.size == SALT_BYTES) { "gesture adaptation salt must be $SALT_BYTES bytes" }
        require(alternativeCapacity > 0) { "alternativeCapacity must be positive" }
        require(rejectionCapacity > 0) { "rejectionCapacity must be positive" }
        require(decayInterval in 1..Long.MAX_VALUE / MAX_STRENGTH) {
            "decayInterval must be positive and bounded"
        }
    }

    /** A verified replacement of [rejected] by [chosen], such as tapping a swipe alternative. */
    @Synchronized
    fun observeAlternative(rejected: String, chosen: String): Boolean {
        val rejectedFingerprint = fingerprint(rejected) ?: return false
        val chosenFingerprint = fingerprint(chosen) ?: return false
        if (rejectedFingerprint == chosenFingerprint) return false
        advanceEpoch()

        val key = PairKey(rejectedFingerprint, chosenFingerprint)
        val current = alternatives[key]
        val strength = effective(current).coerceAtMost(MAX_STRENGTH - ALTERNATIVE_SIGNAL)
        alternatives[key] = Evidence(strength + ALTERNATIVE_SIGNAL, epoch)

        // A later reverse choice is direct counter-evidence, not a second permanent preference.
        val reverse = PairKey(chosenFingerprint, rejectedFingerprint)
        alternatives[reverse]?.let { evidence ->
            val remaining = effective(evidence) - ALTERNATIVE_SIGNAL
            if (remaining > 0) alternatives[reverse] = Evidence(remaining, epoch)
            else alternatives.remove(reverse)
        }
        // Choosing a word also repays one old content-free immediate-undo strike against it.
        rejections[chosenFingerprint]?.let { evidence ->
            val remaining = effective(evidence) - 1
            if (remaining > 0) rejections[chosenFingerprint] = Evidence(remaining, epoch)
            else rejections.remove(chosenFingerprint)
        }
        trimToCapacity()
        return true
    }

    /** A verified immediate whole-word Backspace; deliberately weaker than naming an alternative. */
    @Synchronized
    fun observeImmediateUndo(rejected: String): Boolean {
        val rejectedFingerprint = fingerprint(rejected) ?: return false
        advanceEpoch()
        val current = rejections[rejectedFingerprint]
        rejections[rejectedFingerprint] = Evidence(
            strength = (effective(current) + UNDO_SIGNAL).coerceAtMost(MAX_STRENGTH),
            lastEpoch = epoch,
        )
        trimToCapacity()
        return true
    }

    /** Applies the same bounded rank-space adjustment to neural and deterministic candidates. */
    @Synchronized
    fun rerank(candidates: List<GestureCandidate>): List<GestureCandidate> {
        if (candidates.size < 2 || (alternatives.isEmpty() && rejections.isEmpty())) return candidates
        val ranked = candidates.mapIndexed { index, candidate ->
            Ranked(index, candidate, fingerprint(candidate.word))
        }
        val byFingerprint = HashMap<Long, Ranked>(ranked.size)
        for (candidate in ranked) candidate.fingerprint?.let { byFingerprint.putIfAbsent(it, candidate) }

        for ((key, evidence) in alternatives) {
            val strength = effective(evidence)
            if (strength <= 0) continue
            val rejected = byFingerprint[key.rejected] ?: continue
            val chosen = byFingerprint[key.chosen] ?: continue
            chosen.adjustment += strength * ALTERNATIVE_BONUS_PER_POINT
            rejected.adjustment -= strength * ALTERNATIVE_PENALTY_PER_POINT
        }
        for ((fingerprint, evidence) in rejections) {
            val candidate = byFingerprint[fingerprint] ?: continue
            candidate.adjustment -= effective(evidence) * UNDO_PENALTY_PER_POINT
        }
        if (ranked.none { it.adjustment != 0f }) return candidates

        val reordered = ranked.sortedWith(
            compareByDescending<Ranked> { -it.originalIndex.toFloat() + it.adjustment }
                .thenBy { it.originalIndex },
        )
        if (reordered.indices.all { reordered[it].originalIndex == it }) return candidates

        // Once order changes, expose a finite descending score that obeys GestureCandidate's
        // contract. Raw scores cannot be retained coherently because the two engines' scales are
        // unrelated and the personal adjustment intentionally lives in rank space.
        return reordered.mapIndexed { index, candidate ->
            candidate.candidate.copy(score = (reordered.size - index).toFloat())
        }
    }

    /** Returns a deterministic, bounded snapshot without resetting partial decay age. */
    @Synchronized
    fun snapshot(): GestureAdaptationSnapshot {
        return GestureAdaptationSnapshot(
            version = SNAPSHOT_VERSION,
            saltHex = salt.toHex(),
            epoch = epoch,
            alternatives = alternatives.entries
                .filter { effective(it.value) > 0 }
                .sortedWith(
                    compareBy<Map.Entry<PairKey, Evidence>>(
                        { java.lang.Long.toUnsignedString(it.key.rejected, 16) },
                        { java.lang.Long.toUnsignedString(it.key.chosen, 16) },
                    ),
                )
                .map { (key, evidence) ->
                    GestureAlternativePreference(
                        rejectedFingerprint = key.rejected,
                        chosenFingerprint = key.chosen,
                        strength = evidence.strength,
                        lastEpoch = evidence.lastEpoch,
                    )
                },
            rejections = rejections.entries
                .filter { effective(it.value) > 0 }
                .sortedBy { java.lang.Long.toUnsignedString(it.key, 16) }
                .map { (fingerprint, evidence) ->
                    GestureRejectionPreference(
                        fingerprint = fingerprint,
                        strength = evidence.strength,
                        lastEpoch = evidence.lastEpoch,
                    )
                },
        )
    }

    /**
     * Replaces live state only when the snapshot header is valid; malformed entries are ignored.
     * Returns false when the version/salt/epoch makes the whole snapshot unusable.
     */
    @Synchronized
    fun restore(snapshot: GestureAdaptationSnapshot): Boolean {
        if (snapshot.version != SNAPSHOT_VERSION || snapshot.epoch < 0L) return false
        val restoredSalt = snapshot.saltHex.hexToBytes() ?: return false
        if (restoredSalt.size != SALT_BYTES) return false

        val restoredAlternatives = LinkedHashMap<PairKey, Evidence>()
        for (entry in snapshot.alternatives) {
            if (
                entry.rejectedFingerprint == entry.chosenFingerprint ||
                entry.strength !in 1..MAX_STRENGTH ||
                entry.lastEpoch !in 0..snapshot.epoch
            ) continue
            restoredAlternatives[PairKey(entry.rejectedFingerprint, entry.chosenFingerprint)] =
                Evidence(entry.strength, entry.lastEpoch)
        }
        val restoredRejections = LinkedHashMap<Long, Evidence>()
        for (entry in snapshot.rejections) {
            if (entry.strength !in 1..MAX_STRENGTH || entry.lastEpoch !in 0..snapshot.epoch) continue
            restoredRejections[entry.fingerprint] = Evidence(entry.strength, entry.lastEpoch)
        }

        salt = restoredSalt
        epoch = snapshot.epoch
        alternatives.clear()
        alternatives.putAll(restoredAlternatives)
        rejections.clear()
        rejections.putAll(restoredRejections)
        pruneExpired()
        trimToCapacity()
        return true
    }

    @Synchronized
    fun clear() {
        alternatives.clear()
        rejections.clear()
        epoch = 0L
        // Rotate the salt so fingerprints surviving in an interrupted old file cannot be joined
        // to learning collected after the user explicitly cleared personalized data.
        salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
    }

    @Synchronized
    fun isEmpty(): Boolean = alternatives.isEmpty() && rejections.isEmpty()

    private fun advanceEpoch() {
        if (epoch == Long.MAX_VALUE) {
            compact()
            epoch = 0L
            alternatives.values.forEach { it.lastEpoch = 0L }
            rejections.values.forEach { it.lastEpoch = 0L }
        } else {
            epoch++
        }
        if (epoch % decayInterval == 0L) compact()
    }

    private fun effective(evidence: Evidence?): Int {
        evidence ?: return 0
        val age = (epoch - evidence.lastEpoch).coerceAtLeast(0L)
        val decay = (age / decayInterval).coerceAtMost(MAX_STRENGTH.toLong()).toInt()
        return (evidence.strength - decay).coerceAtLeast(0)
    }

    private fun compact() {
        val alternativeIterator = alternatives.iterator()
        while (alternativeIterator.hasNext()) {
            val entry = alternativeIterator.next()
            val age = (epoch - entry.value.lastEpoch).coerceAtLeast(0L)
            val intervals = (age / decayInterval).coerceAtMost(MAX_STRENGTH.toLong()).toInt()
            val strength = (entry.value.strength - intervals).coerceAtLeast(0)
            if (strength == 0) alternativeIterator.remove()
            else if (intervals > 0) {
                entry.setValue(
                    Evidence(strength, entry.value.lastEpoch + intervals * decayInterval),
                )
            }
        }
        val rejectionIterator = rejections.iterator()
        while (rejectionIterator.hasNext()) {
            val entry = rejectionIterator.next()
            val age = (epoch - entry.value.lastEpoch).coerceAtLeast(0L)
            val intervals = (age / decayInterval).coerceAtMost(MAX_STRENGTH.toLong()).toInt()
            val strength = (entry.value.strength - intervals).coerceAtLeast(0)
            if (strength == 0) rejectionIterator.remove()
            else if (intervals > 0) {
                entry.setValue(
                    Evidence(strength, entry.value.lastEpoch + intervals * decayInterval),
                )
            }
        }
    }

    /** Removes only fully expired rows, preserving the partial logical age of live evidence. */
    private fun pruneExpired() {
        alternatives.entries.removeIf { effective(it.value) == 0 }
        rejections.entries.removeIf { effective(it.value) == 0 }
    }

    private fun trimToCapacity() {
        trim(alternatives, alternativeCapacity)
        trim(rejections, rejectionCapacity)
    }

    private fun <K> trim(values: LinkedHashMap<K, Evidence>, capacity: Int) {
        while (values.size > capacity) {
            val weakest = values.entries.minWithOrNull(
                compareBy<Map.Entry<K, Evidence>>({ effective(it.value) }, { it.value.lastEpoch }),
            ) ?: return
            values.remove(weakest.key)
        }
    }

    private fun fingerprint(word: String): Long? {
        val normalized = word.lowercase(Locale.ROOT)
        if (
            normalized.length !in MIN_WORD_LENGTH..MAX_WORD_LENGTH ||
            normalized.any { it !in 'a'..'z' && it != '\'' }
        ) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val bytes = digest.digest(normalized.toByteArray(StandardCharsets.UTF_8))
        return ByteBuffer.wrap(bytes).long
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0 || any { it !in "0123456789abcdefABCDEF" }) return null
        return runCatching {
            ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    private companion object {
        const val SNAPSHOT_VERSION = 1
        const val SALT_BYTES = 16
        const val MIN_WORD_LENGTH = 2
        const val MAX_WORD_LENGTH = 48
        const val MAX_STRENGTH = 8
        const val ALTERNATIVE_SIGNAL = 2
        const val UNDO_SIGNAL = 1
        const val ALTERNATIVE_BONUS_PER_POINT = 0.65f
        const val ALTERNATIVE_PENALTY_PER_POINT = 0.15f
        const val UNDO_PENALTY_PER_POINT = 0.60f
        const val DEFAULT_ALTERNATIVE_CAPACITY = 256
        const val DEFAULT_REJECTION_CAPACITY = 128
        const val DEFAULT_DECAY_INTERVAL = 64L
    }
}
