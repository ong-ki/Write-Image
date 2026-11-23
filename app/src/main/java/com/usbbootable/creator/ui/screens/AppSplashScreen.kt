package com.usbbootable.creator.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usbbootable.creator.R
import com.usbbootable.creator.ui.theme.CyanAccentBright
import com.usbbootable.creator.ui.theme.ElectricBlue
import com.usbbootable.creator.ui.theme.ElectricBlueBright
import com.usbbootable.creator.ui.theme.GlassBackgroundDeep
import com.usbbootable.creator.ui.theme.GlassTextWhite
import com.usbbootable.creator.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.launch

/**
 * Branded Dark Navy splash handoff matching the Android 12+ system SplashScreen window
 * (`@color/splash_background` + `@drawable/ic_splash_logo`) with zero blank-frame flicker.
 *
 * The logo begins visible at full opacity so the system splash transitions seamlessly into
 * this screen while the title and subtle neon-blue accent fade in, then hands off directly
 * to the user's Light- or Dark-themed Home screen as soon as [isStartupReady] is true.
 */
@Composable
fun AppSplashScreen(
    isStartupReady: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motionEnabled = rememberMotionEnabled()
    val latestOnFinished by rememberUpdatedState(onFinished)

    var animDone by rememberSaveable { mutableStateOf(!motionEnabled) }
    val logoScale = remember { Animatable(if (motionEnabled && !animDone) 0.94f else 1f) }
    val titleAlpha = remember { Animatable(if (motionEnabled && !animDone) 0f else 1f) }
    val accentBrush = remember {
        Brush.horizontalGradient(
            colors = listOf(ElectricBlue, ElectricBlueBright, CyanAccentBright)
        )
    }

    LaunchedEffect(motionEnabled) {
        if (!motionEnabled || animDone) {
            logoScale.snapTo(1f)
            titleAlpha.snapTo(1f)
            animDone = true
            return@LaunchedEffect
        }

        val scaleJob = launch {
            logoScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
            )
        }
        val titleJob = launch {
            titleAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 240, delayMillis = 50, easing = FastOutSlowInEasing)
            )
        }
        scaleJob.join()
        titleJob.join()
        animDone = true
    }

    LaunchedEffect(isStartupReady, animDone) {
        if (isStartupReady && animDone) {
            latestOnFinished()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GlassBackgroundDeep)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .drawWithCache {
                val center = Offset(size.width / 2f, size.height * 0.46f)
                val maxRadius = size.minDimension * 0.50f
                val haloBrush = Brush.radialGradient(
                    colors = listOf(
                        ElectricBlue.copy(alpha = 0.24f),
                        CyanAccentBright.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxRadius
                )
                onDrawBehind {
                    drawCircle(
                        brush = haloBrush,
                        radius = maxRadius,
                        center = center
                    )
                }
            }
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .graphicsLayer {
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_splash_logo),
                    contentDescription = null,
                    modifier = Modifier.size(128.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp
                ),
                color = GlassTextWhite,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = titleAlpha.value
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(2.5.dp)
                    .graphicsLayer {
                        alpha = titleAlpha.value
                        scaleX = logoScale.value
                    }
                    .clip(RoundedCornerShape(50))
                    .background(accentBrush)
            )
        }
    }
}
