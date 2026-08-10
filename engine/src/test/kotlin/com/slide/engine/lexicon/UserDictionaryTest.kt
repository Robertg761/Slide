package com.slide.engine.lexicon

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserDictionaryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dictionary = UserDictionary()

    private fun store(words: File = File(folder.root, "learned.txt")) =
        UserDictionaryStore(words, File(folder.root, "pairs.txt"))

    // region Learning

    /**
     * The rule that keeps a typo from being defended for ever. One occurrence is as likely to be a
     * slip as a word, so it is remembered and not yet believed.
     */
    @Test
    fun `a word typed once is not yet trusted`() {
        dictionary.learn("kubernetes")
        assertFalse(dictionary.isTrusted("kubernetes"))

        dictionary.learn("kubernetes")
        assertTrue(dictionary.isTrusted("kubernetes"))
    }

    @Test
    fun `a strong signal trusts a word at once`() {
        dictionary.learn("robertg", weight = 2)
        assertTrue(dictionary.isTrusted("robertg"))
    }

    @Test
    fun `is not case sensitive`() {
        dictionary.learn("Slide", weight = 2)
        assertTrue(dictionary.isTrusted("slide"))
        assertTrue(dictionary.isTrusted("SLIDE"))
    }

    @Test
    fun `keeps useful casing while matching case insensitively`() {
        dictionary.learn("iPhoneX", weight = 2)
        // A later lowercase sighting is the same word, but carries less presentation information.
        dictionary.learn("iphonex")

        assertTrue(dictionary.isTrusted("IPHONEX"))
        assertEquals(3, dictionary.countOf("iphonex"))
        assertEquals(listOf("iPhoneX"), dictionary.completions("IPHON", limit = 3))
        assertEquals(listOf("iPhoneX" to 3), dictionary.entries())
    }

    @Test
    fun `one accidental casing cannot override an established surface`() {
        repeat(4) { dictionary.learn("kubectl") }
        dictionary.learn("kubeCtl")

        assertEquals(listOf("kubectl"), dictionary.completions("kube", limit = 3))

        // A genuinely repeated new spelling can still become the preferred form.
        repeat(4) { dictionary.learn("kubeCtl") }
        assertEquals(listOf("kubeCtl"), dictionary.completions("kube", limit = 3))
    }

    @Test
    fun `refuses what is not a word`() {
        for (rubbish in listOf("", "a", "x1", "he!lo", "'''", "  ", "a".repeat(40))) {
            dictionary.learn(rubbish, weight = 9)
            assertFalse("learned '$rubbish'", dictionary.isTrusted(rubbish))
        }
    }

    @Test
    fun `forgets on request`() {
        dictionary.learn("mistayk", weight = 9)
        assertTrue(dictionary.isTrusted("mistayk"))

        assertTrue(dictionary.forget("mistayk"))
        assertFalse(dictionary.isTrusted("mistayk"))
        assertEquals(0, dictionary.countOf("mistayk"))
    }

    // endregion

    // region Completions

    @Test
    fun `completes a trusted word`() {
        dictionary.learn("kubernetes", weight = 3)
        assertEquals(listOf("kubernetes"), dictionary.completions("kube", limit = 3))
    }

    /** Offering a word seen once would put the user's own typos in the strip. */
    @Test
    fun `withholds a word it does not yet trust`() {
        dictionary.learn("teh")
        assertTrue(dictionary.completions("te", limit = 3).isEmpty())
    }

    @Test
    fun `offers the most used first`() {
        dictionary.learn("kubernetes", weight = 3)
        dictionary.learn("kubectl", weight = 30)
        assertEquals(listOf("kubectl", "kubernetes"), dictionary.completions("kub", limit = 3))
    }

    /** The word itself is not a completion of itself; the strip already shows what was typed. */
    @Test
    fun `does not offer the prefix back`() {
        dictionary.learn("kubectl", weight = 3)
        assertTrue(dictionary.completions("kubectl", limit = 3).isEmpty())
    }

    // endregion

    @Test
    fun `stays inside its capacity, keeping what is used most`() {
        val small = UserDictionary(capacity = 100)
        for (i in 0 until 500) small.learn("word$i".filter(Char::isLetter) + "abcdefgh".take(i % 8 + 2))
        small.learn("important", weight = 200)

        assertTrue("grew to ${small.size}", small.size <= 100)
        assertTrue("dropped the most used word", small.isTrusted("important"))
    }

    // region Persistence

    @Test
    fun `survives a round trip through the file`() {
        val store = store()
        dictionary.learn("kubectl", weight = 5)
        dictionary.learn("robertg", weight = 2)
        dictionary.learn("seenonce")
        assertTrue(store.save(dictionary))

        val restored = UserDictionary()
        store.load(restored)

        assertEquals(5, restored.countOf("kubectl"))
        assertTrue(restored.isTrusted("robertg"))
        // Counts below the threshold survive too: the second sighting should still trust the word,
        // not start again from nothing because the keyboard was closed in between.
        assertEquals(1, restored.countOf("seenonce"))
        assertFalse(restored.isTrusted("seenonce"))
    }

    @Test
    fun `survives a corrupt file rather than refusing to type`() {
        val file = File(folder.root, "learned.txt")
        file.writeText("kubectl\t5\ngarbage-with-no-count\n\tnope\nrobertg\tnotanumber\nfine\t3\n")

        val restored = UserDictionary()
        store(file).load(restored)

        assertEquals(5, restored.countOf("kubectl"))
        assertEquals(3, restored.countOf("fine"))
        assertEquals(0, restored.countOf("robertg"))
    }

    @Test
    fun `loading a file that is not there leaves an empty dictionary`() {
        val restored = UserDictionary()
        store(File(folder.root, "absent.txt")).load(restored)
        assertEquals(0, restored.size)
    }

    @Test
    fun `surface casing survives a round trip and old lowercase rows remain compatible`() {
        val file = File(folder.root, "learned.txt")
        file.writeText("legacyword\t2\niPhoneX\t5\n")

        val restored = UserDictionary()
        store(file).load(restored)

        assertEquals(listOf("iPhoneX"), restored.completions("iphone", limit = 3))
        assertEquals(listOf("legacyword"), restored.completions("legacy", limit = 3))
    }

    @Test
    fun `restored casing also resists a one-off variant`() {
        val store = store()
        repeat(5) { dictionary.learn("Kubectl") }
        assertTrue(store.save(dictionary))

        val restored = UserDictionary()
        store.load(restored)
        restored.learn("KUBECTL")

        assertEquals(listOf("Kubectl"), restored.completions("kube", limit = 3))
    }

    @Test
    fun `saving replaces an existing file and reports success`() {
        val store = store()
        dictionary.learn("firstword", weight = 4)
        assertTrue(store.save(dictionary))

        dictionary.clear()
        dictionary.learn("SecondWord", weight = 5)
        assertTrue(store.save(dictionary))

        val restored = UserDictionary()
        store.load(restored)
        assertEquals(0, restored.countOf("firstword"))
        assertEquals(5, restored.countOf("secondword"))
        assertEquals(listOf("SecondWord"), restored.completions("second", limit = 3))
    }

    @Test
    fun `a stale fixed temporary file cannot collide with a save`() {
        File(folder.root, "learned.txt.tmp").writeText("left by an older build")
        dictionary.learn("iPhoneX", weight = 3)

        assertTrue(store().save(dictionary))
        assertTrue(File(folder.root, "learned.txt.tmp").exists())
    }

    @Test
    fun `save stages through the configured no-backup directory`() {
        val noBackup = File(folder.root, "no-backup").apply { mkdir() }
        val words = File(folder.root, "learned.txt")
        val pairs = File(folder.root, "pairs.txt")
        val store = UserDictionaryStore(words, pairs, noBackup)
        dictionary.learn("iPhoneX", weight = 3)

        assertTrue(store.save(dictionary))
        assertTrue(words.exists())
        assertTrue("save residue remained in no-backup", noBackup.listFiles().orEmpty().isEmpty())

        dictionary.clear()
        dictionary.learn("SecondWord", weight = 4)
        assertTrue("cross-directory replacement failed", store.save(dictionary))
        val restored = UserDictionary()
        store.load(restored)
        assertEquals(0, restored.countOf("iphonex"))
        assertEquals(4, restored.countOf("secondword"))

        // An invalid configured staging directory must fail even though filesDir is writable. This
        // proves the save did not quietly fall back into the backup domain.
        val invalid = File(folder.root, "not-a-temp-directory").apply { writeText("occupied") }
        assertFalse(UserDictionaryStore(words, pairs, invalid).save(dictionary))
    }

    @Test
    fun `deletion request survives restart and blocks stale load and save until completion`() {
        val noBackup = File(folder.root, "no-backup").apply { mkdir() }
        val words = File(folder.root, "learned.txt")
        val pairsFile = File(folder.root, "pairs.txt")
        val store = UserDictionaryStore(words, pairsFile, noBackup)
        val pairs = UserBigrams().apply { repeat(4) { learn("Sam", "Whitmore") } }
        dictionary.learn("iPhoneX", weight = 3)
        assertTrue(store.save(dictionary))
        assertTrue(store.save(pairs))

        val residues = listOf(
            File(folder.root, "learned.txt.tmp"),
            File(folder.root, "pairs.txt.interrupted.tmp"),
            File(noBackup, "learned.txt.crashed.tmp"),
            File(noBackup, "pairs.txt.crashed.tmp"),
        )
        residues.forEach { it.writeText("partial") }
        val unrelated = File(noBackup, "another-component.tmp").apply { writeText("keep") }

        assertTrue("clear intent was not made durable", store.requestDeletion())
        assertFalse(words.exists())
        assertFalse(pairsFile.exists())
        residues.forEach { assertFalse("left ${it.name}", it.exists()) }
        assertTrue("deleted another component's temporary file", unrelated.exists())

        val marker = File(noBackup, "learned_data.clear_pending")
        assertTrue("request removed its marker before completion", marker.exists())

        // Simulate payload that survived a crash or a failed filesystem deletion. A fresh Store
        // instance must obey the durable marker rather than trusting either file.
        words.writeText("staleWord\t5\n")
        pairsFile.writeText("Sam\tWhitmore\t4\n")
        val afterRestart = UserDictionaryStore(words, pairsFile, noBackup)
        val staleWords = UserDictionary().apply { learn("alreadyloaded", weight = 3) }
        val stalePairs = UserBigrams().apply { repeat(4) { learn("old", "pair") } }
        afterRestart.load(staleWords)
        afterRestart.load(stalePairs)
        assertEquals(0, staleWords.size)
        assertEquals(0, stalePairs.size)
        assertFalse(afterRestart.save(dictionary))
        assertFalse(afterRestart.save(pairs))

        assertTrue(afterRestart.completePendingDeletion())
        assertFalse(marker.exists())
        assertFalse(words.exists())
        assertFalse(pairsFile.exists())
        assertTrue("unrelated no-backup data was removed", unrelated.exists())
        assertTrue("save did not resume after completion", afterRestart.save(dictionary))
    }

    @Test
    fun `failed cleanup retains the marker but still attempts every target and temporary`() {
        val noBackup = File(folder.root, "no-backup").apply { mkdir() }
        val undeletable = File(folder.root, "learned.txt").apply {
            mkdir()
            File(this, "child").writeText("keeps directory non-empty")
        }
        val pairs = File(folder.root, "pairs.txt").apply { writeText("pair data") }
        val residue = File(noBackup, "learned.txt.crashed.tmp").apply { writeText("partial") }
        val store = UserDictionaryStore(undeletable, pairs, noBackup)

        // The request succeeds because its promise is durable intent, not immediate cleanup.
        assertTrue(store.requestDeletion())
        assertTrue("failure fixture unexpectedly disappeared", undeletable.exists())
        assertFalse("pair deletion was skipped after the first failure", pairs.exists())
        assertFalse("temporary cleanup was skipped after failure", residue.exists())
        assertFalse(store.completePendingDeletion())
        assertTrue(File(noBackup, "learned_data.clear_pending").exists())

        val blocked = UserDictionary().apply { learn("stale", weight = 3) }
        store.load(blocked)
        assertEquals(0, blocked.size)
        assertFalse(store.save(dictionary))

        assertTrue(File(undeletable, "child").delete())
        assertTrue(undeletable.delete())
        assertTrue(store.completePendingDeletion())
        assertFalse(File(noBackup, "learned_data.clear_pending").exists())
    }

    @Test
    fun `failed marker persistence never begins destructive cleanup`() {
        val words = File(folder.root, "learned.txt").apply { writeText("keep\t3\n") }
        val pairs = File(folder.root, "pairs.txt").apply { writeText("keep\tpair\t4\n") }
        val invalidNoBackup = File(folder.root, "not-a-directory").apply { writeText("occupied") }
        val store = UserDictionaryStore(words, pairs, invalidNoBackup)

        assertFalse(store.requestDeletion())
        assertTrue("words were deleted without durable clear intent", words.exists())
        assertTrue("pairs were deleted without durable clear intent", pairs.exists())
    }

    @Test
    fun `completion without a pending request is an idempotent no-op`() {
        val store = store()
        dictionary.learn("preserved", weight = 3)
        assertTrue(store.save(dictionary))

        assertTrue(store.completePendingDeletion())
        val restored = UserDictionary()
        store.load(restored)
        assertEquals(3, restored.countOf("preserved"))
    }

    @Test
    fun `separate store instances serialize save against a deletion request`() {
        val noBackup = File(folder.root, "no-backup").apply { mkdir() }
        val words = File(folder.root, "learned.txt")
        val pairs = File(folder.root, "pairs.txt")
        val savingStore = UserDictionaryStore(words, pairs, noBackup)
        val deletingStore = UserDictionaryStore(words, pairs, noBackup)
        val data = UserDictionary().apply { learn("concurrent", weight = 5) }
        val start = CountDownLatch(1)
        val requested = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)

        val saver = thread(name = "learned-data-save") {
            try {
                start.await()
                savingStore.save(data)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        val deleter = thread(name = "learned-data-delete") {
            try {
                start.await()
                requested.set(deletingStore.requestDeletion())
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        start.countDown()
        saver.join()
        deleter.join()

        assertEquals(null, failure.get())
        assertTrue("deletion request lost the race", requested.get())
        val probe = UserDictionary().apply { learn("mustbecleared", weight = 3) }
        savingStore.load(probe)
        assertEquals(0, probe.size)
        assertFalse("save resumed while the marker was pending", savingStore.save(data))
        assertTrue(deletingStore.completePendingDeletion())
        assertFalse(words.exists())
    }

    @Test
    fun `save failure is reported without throwing`() {
        val notDirectory = File(folder.root, "not-a-directory").apply { writeText("occupied") }
        val broken = UserDictionaryStore(
            File(notDirectory, "learned.txt"),
            File(notDirectory, "pairs.txt"),
        )
        dictionary.learn("anything", weight = 2)

        assertFalse(broken.save(dictionary))
        assertFalse(broken.save(UserBigrams()))
    }

    @Test
    fun `pairs survive a round trip through their own file`() {
        val store = store()
        val pairs = UserBigrams()
        repeat(5) { pairs.learn("kubectl", "apply") }
        repeat(4) { pairs.learn("Sam", "Whitmore") }
        assertTrue(store.save(pairs))

        val restored = UserBigrams()
        store.load(restored)

        assertEquals(5, restored.successorsOf("kubectl")["apply"])
        assertEquals(4, restored.successorsOf("sam")["Whitmore"])
        assertEquals(0.5f, restored.score("SAM", "WHITMORE"), 0f)
    }

    @Test
    fun `a corrupt pair file does not stop the words loading`() {
        val words = File(folder.root, "learned.txt")
        val pairFile = File(folder.root, "pairs.txt")
        words.writeText("kubectl\t5\n")
        pairFile.writeText("only-two\tfields\nkubectl\tapply\tnotanumber\ngood\tpair\t4\n")

        val restoredWords = UserDictionary()
        val restoredPairs = UserBigrams()
        store(words).load(restoredWords)
        store(words).load(restoredPairs)

        assertEquals(5, restoredWords.countOf("kubectl"))
        assertEquals(4, restoredPairs.successorsOf("good")["pair"])
        assertEquals(1, restoredPairs.size)
    }

    // endregion
}
