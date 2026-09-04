package com.slide.ime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM guard for coroutine wiring that otherwise needs a real InputMethodService host.
 *
 * An exception escaping a service coroutine used to reach the thread's uncaught handler and take
 * the keyboard process down under whatever app the user was in. These checks keep the handler on
 * every scope the service constructs, and keep the two learned-data coroutines returning their
 * persistence tickets on failure, so the handler never leaves the state machine stuck instead.
 */
class SlideInputMethodServiceScopeWiringTest {
    private val source = run {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }.first { File(it, "settings.gradle.kts").isFile }
        File(
            root,
            "ime/src/main/kotlin/com/slide/ime/SlideInputMethodService.kt",
        ).readText()
    }

    @Test
    fun `every coroutine scope the service constructs carries an exception handler`() {
        val constructions = Regex("""CoroutineScope\(""").findAll(source).toList()
        assertTrue("expected at least the service scope and the finalizer scope", constructions.size >= 2)
        constructions.forEach { match ->
            // A scope's context is a single expression; the handler must be in that expression.
            val statement = source.substring(match.range.first, closingParen(match.range.first + "CoroutineScope".length))
            assertTrue(
                "scope at offset ${match.range.first} has no CoroutineExceptionHandler: $statement",
                statement.contains("CoroutineExceptionHandler") || statement.contains("uncaughtFailureHandler"),
            )
        }
    }

    @Test
    fun `learned-data save and delete hand their tickets back on failure`() {
        assertOrdered(
            method("saveLearnedWords", "LearnedDataSnapshot"),
            "catch (failure: CancellationException)",
            "catch (failure: Exception)",
            "LearnedDataWriteResult(saved = false, pendingDeleteResolved = false)",
            "learnedPersistence.finishSave(",
        )
        assertOrdered(
            method("scheduleLearnedDataDelete", "deleteLearnedDataWithRetry(): Boolean"),
            "catch (failure: CancellationException)",
            "catch (failure: Exception)",
            "learnedPersistence.finishDeletion(ticket, deleted)",
        )
    }

    private fun closingParen(openIndex: Int): Int {
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return index + 1
            }
        }
        error("unbalanced parentheses from offset $openIndex")
    }

    private fun method(name: String, nextSymbol: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("method $name is missing", start >= 0)
        val end = source.indexOf(nextSymbol, start)
        assertTrue("could not bound $name by $nextSymbol", end > start)
        return source.substring(start, end)
    }

    private fun assertOrdered(text: String, vararg needles: String) {
        var from = 0
        needles.forEach { needle ->
            val at = text.indexOf(needle, from)
            assertTrue("expected `$needle` after offset $from in:\n$text", at >= 0)
            from = at + needle.length
        }
    }
}
