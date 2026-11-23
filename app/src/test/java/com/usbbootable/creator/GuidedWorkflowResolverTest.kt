package com.usbbootable.creator

import com.usbbootable.creator.core.image.DetectedOsFamily
import com.usbbootable.creator.core.image.FlashingMode
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.ui.GuidedStep
import com.usbbootable.creator.ui.GuidedWorkflowResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Home-screen guidance advances only from real state changes and returns to the
 * correct step when USB, ISO, or mode state is invalidated.
 */
class GuidedWorkflowResolverTest {

    private fun resolve(
        phase: WritePhase = WritePhase.IDLE,
        drive: Boolean = false,
        iso: Boolean = false,
        mode: Boolean = false,
        canCreate: Boolean = true,
        driveError: Boolean = false,
        isoError: Boolean = false,
        modeError: Boolean = false
    ) = GuidedWorkflowResolver.resolve(
        writePhase = phase,
        driveReady = drive,
        isoReady = iso,
        modeReady = mode,
        canCreate = canCreate,
        driveHasError = driveError,
        isoHasError = isoError,
        modeHasError = modeError
    )

    @Test
    fun exactAutomaticSequence() {
        assertEquals(GuidedStep.USB_DRIVE, resolve().currentStep)
        assertEquals(GuidedStep.ISO_IMAGE, resolve(drive = true).currentStep)
        assertEquals(GuidedStep.FLASHING_MODE, resolve(drive = true, iso = true).currentStep)
        assertEquals(GuidedStep.CREATE, resolve(drive = true, iso = true, mode = true).currentStep)
    }

    @Test
    fun onlyOneStepIsAnimatedAndPrerequisitesGetCheckmarks() {
        val state = resolve(drive = true, iso = true)
        assertEquals(GuidedStep.FLASHING_MODE, state.currentStep)
        assertTrue(state.isCompleted(GuidedStep.USB_DRIVE))
        assertTrue(state.isCompleted(GuidedStep.ISO_IMAGE))
        assertFalse(state.isCompleted(GuidedStep.FLASHING_MODE))
        assertFalse(state.isCompleted(GuidedStep.CREATE))
    }

    @Test
    fun usbDisconnectReturnsToStepOneAndClearsLaterChecks() {
        val before = resolve(drive = true, iso = true, mode = true)
        assertTrue(before.isCompleted(GuidedStep.USB_DRIVE))
        assertTrue(before.isCompleted(GuidedStep.ISO_IMAGE))
        assertTrue(before.isCompleted(GuidedStep.FLASHING_MODE))

        val after = resolve(drive = false, iso = true, mode = true)
        assertEquals(GuidedStep.USB_DRIVE, after.currentStep)
        assertTrue(after.completedSteps.isEmpty())
    }

    @Test
    fun isoRemovalReturnsToStepTwo() {
        val state = resolve(drive = true, iso = false, mode = true, isoError = true)
        assertEquals(GuidedStep.ISO_IMAGE, state.currentStep)
        assertTrue(state.isCompleted(GuidedStep.USB_DRIVE))
        assertFalse(state.isCompleted(GuidedStep.ISO_IMAGE))
        assertFalse(state.isCompleted(GuidedStep.FLASHING_MODE))
        assertTrue(state.currentStepHasError)
    }

    @Test
    fun invalidModeReturnsToStepThree() {
        val state = resolve(drive = true, iso = true, mode = false, modeError = true)
        assertEquals(GuidedStep.FLASHING_MODE, state.currentStep)
        assertTrue(state.isCurrent(GuidedStep.FLASHING_MODE))
        assertTrue(state.currentStepHasError)
    }

    @Test
    fun flashingSuppressesAllGuidance() {
        val states = listOf(
            resolve(phase = WritePhase.PREPARING),
            resolve(phase = WritePhase.WRITING),
            resolve(phase = WritePhase.COMPLETED),
            resolve(phase = WritePhase.FAILED)
        )
        states.forEach {
            assertEquals(GuidedStep.NONE, it.currentStep)
            assertFalse(it.currentStepHasError)
        }
    }

    @Test
    fun createStepReportsErrorWhenPreflightStillBlocks() {
        val state = resolve(drive = true, iso = true, mode = true, canCreate = false)
        assertEquals(GuidedStep.CREATE, state.currentStep)
        assertTrue(state.currentStepHasError)
    }

    @Test
    fun modeCompatibilityFollowsIsoFamily() {
        assertTrue(
            GuidedWorkflowResolver.isFlashingModeCompatible(
                FlashingMode.AUTO,
                DetectedOsFamily.UNSUPPORTED
            )
        )
        assertTrue(
            GuidedWorkflowResolver.isFlashingModeCompatible(
                FlashingMode.WINDOWS,
                DetectedOsFamily.WINDOWS
            )
        )
        assertFalse(
            GuidedWorkflowResolver.isFlashingModeCompatible(
                FlashingMode.WINDOWS,
                DetectedOsFamily.LINUX
            )
        )
        assertTrue(
            GuidedWorkflowResolver.isFlashingModeCompatible(
                FlashingMode.LINUX,
                DetectedOsFamily.LINUX
            )
        )
        assertFalse(
            GuidedWorkflowResolver.isFlashingModeCompatible(
                FlashingMode.LINUX,
                DetectedOsFamily.WINDOWS
            )
        )
    }
}
