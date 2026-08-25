package com.slide.ime.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which seek-bar movements become published settings, per input source. */
class SliderCommitPolicyTest {

    @Test
    fun `a finger drag does not commit until it lifts`() {
        // Mid-drag: the label tracks, but nothing publishes.
        assertFalse(
            SliderCommitPolicy.commitsOnProgress(fromUser = true, tracking = true, binding = false),
        )
        assertTrue(
            SliderCommitPolicy.commitsOnProgress(fromUser = true, tracking = false, binding = false),
        )
    }

    @Test
    fun `talkback and rotary steps commit without tracking callbacks`() {
        // Accessibility slider actions and hardware/rotary input never start or stop tracking;
        // if these did not commit from onProgressChanged, the label would show a value that was
        // never applied.
        assertTrue(
            SliderCommitPolicy.commitsOnProgress(fromUser = true, tracking = false, binding = false),
        )
    }

    @Test
    fun `programmatic and rebind updates never commit`() {
        assertFalse(
            SliderCommitPolicy.commitsOnProgress(fromUser = false, tracking = false, binding = false),
        )
        assertFalse(
            SliderCommitPolicy.commitsOnProgress(fromUser = true, tracking = false, binding = true),
        )
    }

    @Test
    fun `an unchanged position is not republished`() {
        // A tap landing where the setting already was, or TalkBack re-stepping onto it, would
        // relayout the keyboard for nothing.
        assertFalse(SliderCommitPolicy.valueChanged(progress = 5, lastPublishedProgress = 5))
        assertTrue(SliderCommitPolicy.valueChanged(progress = 6, lastPublishedProgress = 5))
        assertTrue(SliderCommitPolicy.valueChanged(progress = 4, lastPublishedProgress = 5))
    }
}
