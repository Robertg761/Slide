package com.slide.engine.lexicon

import com.slide.engine.gesture.GestureAdaptation
import com.slide.engine.gesture.GestureCandidate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GestureAdaptationStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `gesture preferences round trip without plaintext and participate in deletion`() {
        val words = File(folder.root, "words.txt")
        val pairs = File(folder.root, "pairs.txt")
        val staging = folder.newFolder("no-backup")
        val touches = File(folder.root, "touches.txt")
        val adaptationFile = File(folder.root, "adaptation.txt")
        val store = UserDictionaryStore(words, pairs, staging, touches, adaptationFile)
        val model = GestureAdaptation()
        model.observeAlternative("home", "hem")
        model.observeImmediateUndo("there")
        assertTrue(store.save(model))

        val persisted = adaptationFile.readText().lowercase()
        assertFalse("home" in persisted)
        assertFalse("hem" in persisted)
        assertFalse("there" in persisted)

        val restored = GestureAdaptation()
        store.load(restored)
        assertEquals(
            "hem",
            restored.rerank(
                listOf(GestureCandidate("home", 2f), GestureCandidate("hem", 1f)),
            ).first().word,
        )

        assertTrue(store.requestDeletion())
        assertFalse(adaptationFile.exists())
        assertTrue(store.completePendingDeletion())
    }

    @Test
    fun `corrupt gesture rows do not block valid preferences`() {
        val adaptationFile = File(folder.root, "adaptation.txt")
        val store = UserDictionaryStore(
            File(folder.root, "words.txt"),
            File(folder.root, "pairs.txt"),
            folder.newFolder("no-backup-corrupt"),
            File(folder.root, "touches.txt"),
            adaptationFile,
        )
        val source = GestureAdaptation().apply { observeAlternative("home", "hem") }
        assertTrue(store.save(source))
        adaptationFile.appendText(
            "alternative\tnot-hex\t0000000000000001\t999\t-4\n" +
                "rejection\t0000000000000001\tnot-a-count\t0\n" +
                "unknown\tprivate text must be ignored\n",
        )

        val restored = GestureAdaptation()
        store.load(restored)

        assertEquals(
            "hem",
            restored.rerank(
                listOf(GestureCandidate("home", 2f), GestureCandidate("hem", 1f)),
            ).first().word,
        )
    }
}
