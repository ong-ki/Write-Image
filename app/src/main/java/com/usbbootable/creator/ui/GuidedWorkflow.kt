package com.usbbootable.creator.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.usbbootable.creator.core.image.DetectedOsFamily
import com.usbbootable.creator.core.image.FlashingMode
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.ui.theme.rememberMotionEnabled

/** The four guided Home-screen steps, in the exact order the user completes them. */
enum class GuidedStep {
    USB_DRIVE,
    ISO_IMAGE,
    FLASHING_MODE,
    CREATE,
    NONE
}

/**
 * Centralized guidance state derived from real application state. Only the single [currentStep]
 * text label blinks; cards, borders, backgrounds, and icons stay completely static.
 */
data class GuidedWorkflowState(
    val currentStep: GuidedStep = GuidedStep.NONE,
    val completedSteps: Set<GuidedStep> = emptySet(),
    val currentStepHasError: Boolean = false
) {
    fun isCurrent(step: GuidedStep): Boolean = currentStep == step
    fun isCompleted(step: GuidedStep): Boolean = step in completedSteps
}

/**
 * Pure resolver so workflow transitions can be verified without Android/Compose. It never reads
 * clocks or observers: every input is an actual state value already held by the ViewModel.
 */
object GuidedWorkflowResolver {

    /**
     * Mirrors PreflightValidator's mode/ISO compatibility rules. AUTO always works; WINDOWS
     * requires a detected Windows ISO; LINUX must not be forced onto a Windows UDF ISO.
     */
    fun isFlashingModeCompatible(
        mode: FlashingMode,
        isoFamily: DetectedOsFamily?
    ): Boolean = when (mode) {
        FlashingMode.AUTO -> true
        FlashingMode.WINDOWS -> isoFamily == DetectedOsFamily.WINDOWS
        FlashingMode.LINUX -> isoFamily != DetectedOsFamily.WINDOWS
    }

    fun resolve(
        writePhase: WritePhase,
        driveReady: Boolean,
        isoReady: Boolean,
        modeReady: Boolean,
        canCreate: Boolean,
        driveHasError: Boolean,
        isoHasError: Boolean,
        modeHasError: Boolean
    ): GuidedWorkflowState {
        // Completion is sequential: 1. USB Drive -> 2. ISO Image -> 3. Flashing Mode -> 4. Create.
        // Disconnecting USB or invalidating an earlier step returns focus to that step and clears
        // later checks.
        val driveDone = driveReady
        val isoDone = driveDone && isoReady
        val modeDone = isoDone && modeReady
        val completed = mutableSetOf<GuidedStep>().apply {
            if (driveDone) add(GuidedStep.USB_DRIVE)
            if (isoDone) add(GuidedStep.ISO_IMAGE)
            if (modeDone) add(GuidedStep.FLASHING_MODE)
        }

        // Once flashing starts (or finishes/fails), guidance is suppressed so the existing
        // operation status, completion state, or error state remains authoritative.
        if (writePhase != WritePhase.IDLE) {
            return GuidedWorkflowState(
                currentStep = GuidedStep.NONE,
                completedSteps = completed
            )
        }

        val current = when {
            !driveReady -> GuidedStep.USB_DRIVE
            !isoReady -> GuidedStep.ISO_IMAGE
            !modeReady -> GuidedStep.FLASHING_MODE
            else -> GuidedStep.CREATE
        }
        val hasError = when (current) {
            GuidedStep.USB_DRIVE -> driveHasError
            GuidedStep.ISO_IMAGE -> isoHasError
            GuidedStep.FLASHING_MODE -> modeHasError
            // All prerequisites are satisfied, so any remaining blocker belongs to Create.
            GuidedStep.CREATE -> !canCreate
            GuidedStep.NONE -> false
        }

        return GuidedWorkflowState(
            currentStep = current,
            completedSteps = completed,
            currentStepHasError = hasError
        )
    }
}

/**
 * Renders a label whose text alone pulses gently in opacity using the active neon accent color
 * when [guideActive] is true.
 *
 * The animated float is read strictly inside [graphicsLayer], so animation frames never trigger
 * recomposition or layout passes and never animate enclosing cards, borders, or icons.
 */
@Composable
fun GuidedStepText(
    text: String,
    style: TextStyle,
    defaultColor: Color,
    guideActive: Boolean,
    guideColor: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    minAlpha: Float = 0.38f,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    val motionEnabled = rememberMotionEnabled()
    val alphaState = if (guideActive && motionEnabled) {
        rememberInfiniteTransition(label = "guided_label_blink").animateFloat(
            initialValue = 1f,
            targetValue = minAlpha,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "guided_label_alpha"
        )
    } else {
        null
    }

    Text(
        text = text,
        style = style,
        fontWeight = fontWeight,
        color = if (guideActive) guideColor else defaultColor,
        maxLines = maxLines,
        overflow = overflow,
        modifier = if (alphaState != null) {
            modifier.graphicsLayer { alpha = alphaState.value }
        } else {
            modifier
        }
    )
}
