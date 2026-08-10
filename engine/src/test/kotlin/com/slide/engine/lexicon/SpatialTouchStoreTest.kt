package com.slide.engine.lexicon

import com.slide.engine.gesture.GestureFixtures
import com.slide.engine.suggest.SpatialTouchModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpatialTouchStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `touch offsets round-trip and participate in learned-data deletion`() {
        val words = File(folder.root, "words.txt")
        val pairs = File(folder.root, "pairs.txt")
        val staging = folder.newFolder("no-backup")
        val touchesFile = File(folder.root, "touches.txt")
        val store = UserDictionaryStore(words, pairs, staging, touchesFile)

        val keys = GestureFixtures.qwerty()
        val touches = floatArrayOf(keys.centerX('t'), keys.centerY('t'))
        val model = SpatialTouchModel()
        assertEquals(1, model.observe("t", "t", touches, keys))
        assertTrue(store.save(model))

        val restored = SpatialTouchModel()
        store.load(restored)
        assertEquals(model.entries(), restored.entries())

        assertTrue(store.requestDeletion())
        assertFalse(touchesFile.exists())
        assertTrue(store.completePendingDeletion())
    }
}
