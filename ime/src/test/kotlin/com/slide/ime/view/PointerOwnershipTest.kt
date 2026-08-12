package com.slide.ime.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-touch ownership rules behind the keyboard's single-slot popups.
 *
 * The Android plumbing around these cannot run off-device; the decisions can, and every case here
 * is one a second finger used to get wrong.
 */
class PointerOwnershipTest {

    @Test
    fun `only the finger that opened the alternates popup may answer it`() {
        assertTrue(PointerOwnership.ownsPopup(popupShowing = true, owner = 1, pointerId = 1))
        // A second finger tapping elsewhere used to commit finger 1's accent against its own key.
        assertFalse(PointerOwnership.ownsPopup(popupShowing = true, owner = 1, pointerId = 2))
    }

    @Test
    fun `a lift with no popup showing is an ordinary key up`() {
        assertFalse(PointerOwnership.ownsPopup(popupShowing = false, owner = 1, pointerId = 1))
        assertFalse(PointerOwnership.ownsPopup(popupShowing = false, owner = null, pointerId = 1))
    }

    @Test
    fun `a hold cannot steal a popup another finger is still choosing from`() {
        assertFalse(PointerOwnership.mayOpenPopup(popupShowing = true, owner = 1, pointerId = 2))
        // Re-arming its own popup is fine, as is opening one when nothing is showing.
        assertTrue(PointerOwnership.mayOpenPopup(popupShowing = true, owner = 1, pointerId = 1))
        assertTrue(PointerOwnership.mayOpenPopup(popupShowing = false, owner = 1, pointerId = 2))
        assertTrue(PointerOwnership.mayOpenPopup(popupShowing = true, owner = null, pointerId = 2))
    }

    @Test
    fun `the key preview follows its own finger`() {
        assertTrue(PointerOwnership.ownsPreview(owner = 3, pointerId = 3))
        // Rollover: the trailing thumb's lift must leave the leading thumb's preview alone.
        assertFalse(PointerOwnership.ownsPreview(owner = 3, pointerId = 4))
        assertTrue(PointerOwnership.ownsPreview(owner = null, pointerId = 4))
    }

    @Test
    fun `a pointer cancelled in padding cannot inherit another fingers preview`() {
        assertFalse(
            PointerOwnership.mayInheritPreview(
                isGesturePointer = false,
                cancelled = true,
                isCharacter = true,
                longPressFired = false,
                cursorMove = false,
                deleteWordGesture = false,
            ),
        )
        assertTrue(
            PointerOwnership.mayInheritPreview(
                isGesturePointer = false,
                cancelled = false,
                isCharacter = true,
                longPressFired = false,
                cursorMove = false,
                deleteWordGesture = false,
            ),
        )
    }

    @Test
    fun `contacts during a swipe are not key presses`() {
        assertTrue(PointerOwnership.ignoresKeyDown(gesturePointerId = 0))
        assertFalse(PointerOwnership.ignoresKeyDown(gesturePointerId = null))
    }

    @Test
    fun `direct rollover cancels backspace repeat`() {
        val different = KeyPressRouting.pendingCancellationForRollover(overDifferentKey = true)
        val same = KeyPressRouting.pendingCancellationForRollover(overDifferentKey = false)

        assertTrue(different.repeat)
        assertFalse(same.repeat)
    }

    @Test
    fun `direct rollover cancels the original key alternate popup timer`() {
        val different = KeyPressRouting.pendingCancellationForRollover(overDifferentKey = true)
        val same = KeyPressRouting.pendingCancellationForRollover(overDifferentKey = false)

        assertTrue(different.longPress)
        assertFalse(same.longPress)
    }

    @Test
    fun `a contact cancelled in padding lets its old key fade`() {
        assertFalse(
            KeyPressRouting.isVisuallyHeld(
                isGesturePointer = false,
                cancelled = true,
                isPlacedKey = true,
            ),
        )
        assertTrue(
            KeyPressRouting.isVisuallyHeld(
                isGesturePointer = false,
                cancelled = false,
                isPlacedKey = true,
            ),
        )
        assertFalse(
            KeyPressRouting.isVisuallyHeld(
                isGesturePointer = true,
                cancelled = false,
                isPlacedKey = true,
            ),
        )
    }

    @Test
    fun `lifting in padding does not resurrect the old key highlight`() {
        assertFalse(
            KeyPressRouting.shouldFadeOnRelease(
                cancelled = true,
                isGesturePointer = false,
            ),
        )
        assertTrue(
            KeyPressRouting.shouldFadeOnRelease(
                cancelled = false,
                isGesturePointer = false,
            ),
        )
    }

    @Test
    fun `gesture lift does not restart the start key fade`() {
        assertFalse(
            KeyPressRouting.shouldFadeOnRelease(
                cancelled = false,
                isGesturePointer = true,
            ),
        )
    }

    @Test
    fun `authoritative up on original key revives a press cancelled in padding`() {
        val release = KeyPressRouting.resolveRelease(
            hasValidKey = true,
            isCurrentKey = true,
            movedBeyondSlop = true,
            wasSlidOff = true,
        )

        assertFalse(release.cancelled)
        assertTrue(release.slidOff)
        assertFalse(release.retarget)
    }

    @Test
    fun `sparse up over another key retargets and clears cancellation`() {
        val release = KeyPressRouting.resolveRelease(
            hasValidKey = true,
            isCurrentKey = false,
            movedBeyondSlop = true,
            wasSlidOff = false,
        )

        assertFalse(release.cancelled)
        assertTrue(release.slidOff)
        assertTrue(release.retarget)
    }

    @Test
    fun `authoritative up retargets after an earlier rollover even within down slop`() {
        val release = KeyPressRouting.resolveRelease(
            hasValidKey = true,
            isCurrentKey = false,
            movedBeyondSlop = false,
            wasSlidOff = true,
        )

        assertFalse(release.cancelled)
        assertTrue(release.slidOff)
        assertTrue(release.retarget)
    }

    @Test
    fun `authoritative up in padding remains cancelled`() {
        val release = KeyPressRouting.resolveRelease(
            hasValidKey = false,
            isCurrentKey = false,
            movedBeyondSlop = true,
            wasSlidOff = true,
        )

        assertTrue(release.cancelled)
        assertTrue(release.slidOff)
        assertFalse(release.retarget)
    }

    @Test
    fun `the search header claims one finger and swallows the rest`() {
        assertEquals(
            SearchHeaderRouting.Down.CLAIM,
            SearchHeaderRouting.onPointerDown(headerOwner = null, inHeader = true),
        )
        // Swallowed rather than passed on: the keys have no gap under the header to land in.
        assertEquals(
            SearchHeaderRouting.Down.SWALLOW,
            SearchHeaderRouting.onPointerDown(headerOwner = 0, inHeader = true),
        )
        assertEquals(
            SearchHeaderRouting.Down.PASS_TO_KEYS,
            SearchHeaderRouting.onPointerDown(headerOwner = null, inHeader = false),
        )
        // Typing while a finger rests in the header still reaches the keys.
        assertEquals(
            SearchHeaderRouting.Down.PASS_TO_KEYS,
            SearchHeaderRouting.onPointerDown(headerOwner = 0, inHeader = false),
        )
    }

    @Test
    fun `only the header's own finger resolves it, whichever lift it arrives as`() {
        assertTrue(SearchHeaderRouting.resolvesOnLift(headerOwner = 0, pointerId = 0))
        assertFalse(SearchHeaderRouting.resolvesOnLift(headerOwner = 0, pointerId = 1))
        assertFalse(SearchHeaderRouting.resolvesOnLift(headerOwner = null, pointerId = 0))
    }

    @Test
    fun `the emoji grid hands over to a finger that is still down`() {
        // Three fingers, the middle one lifts: index 0 survives.
        assertEquals(0, EmojiGridPointer.successorIndex(pointerCount = 3, liftedIndex = 1))
        // The first one lifts: the successor is the next index, not index 0.
        assertEquals(1, EmojiGridPointer.successorIndex(pointerCount = 2, liftedIndex = 0))
        assertEquals(0, EmojiGridPointer.successorIndex(pointerCount = 2, liftedIndex = 1))
        assertNull(EmojiGridPointer.successorIndex(pointerCount = 1, liftedIndex = 0))
    }
}
