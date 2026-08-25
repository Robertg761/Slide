package com.slide.ime.view

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-touch ownership rule behind the suggestion strip's single press slot.
 *
 * The Android plumbing around this cannot run off-device; the decision can, and it is one a
 * resting second thumb used to get wrong.
 */
class StripPointerRoutingTest {

    @Test
    fun `only the pressing finger's lift resolves anything`() {
        assertTrue(
            StripPointerRouting.liftIsAuthoritative(activePointerId = 3, liftedPointerId = 3),
        )
        // A trailing finger lifting first must leave the armed hold and pressed flags alone.
        assertFalse(
            StripPointerRouting.liftIsAuthoritative(activePointerId = 3, liftedPointerId = 4),
        )
    }

    @Test
    fun `a lift with no press owned resolves nothing`() {
        val invalid = MotionEvent.INVALID_POINTER_ID

        // After a second-finger releasePress or an ACTION_CANCEL, no lift may fire a stale tap.
        assertFalse(StripPointerRouting.liftIsAuthoritative(invalid, liftedPointerId = 0))
        assertFalse(StripPointerRouting.liftIsAuthoritative(invalid, invalid))
    }
}
