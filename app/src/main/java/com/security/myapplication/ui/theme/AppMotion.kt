package com.security.myapplication.ui.theme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

// ════════════════════════════════════════════════════════════════════════
// PUMP BOUNCE SCROLL — WhatsApp / iOS style rubber-band overscroll
// ════════════════════════════════════════════════════════════════════════
//
// USAGE:
//
//   NoNativeOverscroll {
//       LazyColumn(modifier = Modifier.pumpBounceScroll()) {
//           items(messages, key = { it.id }) { ChatBubble(it) }
//       }
//   }
//
// Wrapping with NoNativeOverscroll() switches off Android's built-in
// stretch/glow overscroll so it doesn't double up with this custom bounce.
// ════════════════════════════════════════════════════════════════════════

/**
 * WhatsApp / iOS-style rubber-band "pump" overscroll modifier.
 *
 * - Drag past the top/bottom edge  -> content stretches with easing resistance
 *   (the further you pull, the harder it resists — never a hard wall).
 * - Release mid-drag                -> content springs back naturally.
 * - Fast fling into the edge        -> content "pumps" outward briefly,
 *   then settles with a soft spring — the exact tactile bounce WhatsApp's
 *   chat list has when you fling to the very top or bottom.
 * - Fully density-independent: every distance is defined in dp and
 *   converted to px via the current [LocalDensity], so the bounce feels
 *   identical on every screen size and pixel density.
 * - Zero-lag: the offset is updated with [CoroutineStart.UNDISPATCHED] so
 *   it tracks the finger on the same frame, not one frame behind.
 *
 * @param maxBounce     how far the content can stretch past the edge.
 * @param dampingRatio  spring damping for the settle-back animation
 *                      (lower = bouncier, WhatsApp-like default is 0.72).
 * @param stiffness     spring stiffness for the settle-back animation
 *                      (higher = snappier).
 */
@Composable
fun Modifier.pumpBounceScroll(
    maxBounce: Dp = 42.dp,
    dampingRatio: Float = 0.76f,
    stiffness: Float = 380f
): Modifier {
    val density = LocalDensity.current
    val maxBouncePx = with(density) { maxBounce.toPx() }

    val overscrollOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val settleSpec = remember(dampingRatio, stiffness) {
        spring<Float>(dampingRatio = dampingRatio, stiffness = stiffness)
    }

    val nestedScrollConnection = remember(maxBouncePx, settleSpec) {
        object : NestedScrollConnection {
            var isSettling = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // If user touches and drags while an animation is settling, stop it immediately for instant 1:1 control
                if (source == NestedScrollSource.Drag && isSettling) {
                    isSettling = false
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        overscrollOffset.stop()
                    }
                }

                val current = overscrollOffset.value
                if (abs(current) < 0.5f || source != NestedScrollSource.Drag) {
                    return Offset.Zero
                }

                val delta = available.y
                val isReturning = (current > 0f && delta < 0f) || (current < 0f && delta > 0f)
                if (isReturning) {
                    val next = current + delta * 0.55f
                    if ((current > 0f && next <= 0f) || (current < 0f && next >= 0f)) {
                        // Consumed just enough to return to center
                        val consumedDelta = -current / 0.55f
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            overscrollOffset.snapTo(0f)
                        }
                        return Offset(0f, consumedDelta)
                    } else {
                        // Partially returned
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            overscrollOffset.snapTo(next)
                        }
                        return Offset(0f, delta)
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // Only stretch when actively dragging past the edge
                if (source == NestedScrollSource.Drag && available.y != 0f) {
                    val current = overscrollOffset.value
                    val progress = (abs(current) / maxBouncePx).coerceIn(0f, 0.95f)
                    // Logarithmic rubber-band resistance curve
                    val resistance = 0.42f * (1f - progress * progress)
                    val delta = available.y * resistance
                    val next = (current + delta).coerceIn(-maxBouncePx, maxBouncePx)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        overscrollOffset.snapTo(next)
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (abs(overscrollOffset.value) >= 0.5f) {
                    isSettling = true
                    overscrollOffset.animateTo(0f, animationSpec = settleSpec)
                    isSettling = false
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val vy = available.y
                // Smooth momentum absorption:
                // Instead of snapTo (which caused a jarring discontinuous jolt / pump),
                // we pass the leftover fling velocity as the initial velocity into the spring.
                // The spring naturally carries the momentum into a smooth curve and settles back to 0.
                if (abs(vy) > 80f) {
                    isSettling = true
                    val initialVel = (vy * 0.14f).coerceIn(-maxBouncePx * 14f, maxBouncePx * 14f)
                    overscrollOffset.animateTo(
                        targetValue = 0f,
                        initialVelocity = initialVel,
                        animationSpec = settleSpec
                    )
                    isSettling = false
                } else if (abs(overscrollOffset.value) >= 0.5f) {
                    isSettling = true
                    overscrollOffset.animateTo(0f, animationSpec = settleSpec)
                    isSettling = false
                }
                return super.onPostFling(consumed, available)
            }
        }
    }

    return this
        .nestedScroll(nestedScrollConnection)
        .graphicsLayer { translationY = overscrollOffset.value }
}

/**
 * Wrap any scrollable content that uses [pumpBounceScroll] in this, so
 * Android's own stretch/glow overscroll is switched off for it — otherwise
 * both effects would fire at once and the bounce would look doubled/janky.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoNativeOverscroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollConfiguration provides null, content = content)
}

/**
 * AppMotion — Google Material You Motion System
 *
 * Single source of truth for ALL animations in this app.
 * Follows Material 3 motion principles:
 *   - Expressive: enters faster / more decisively than exits
 *   - Spatial: slides slightly for orientation
 *   - Smooth: cubic easing curves — never linear
 *
 * Usage:
 *   enter = AppMotion.screenEnter
 *   exit  = AppMotion.screenExit
 */
object AppMotion {

    // ── Duration Tokens ──────────────────────────────────────────────────────
    // Material 3 recommended durations
    const val SHORT1  = 50
    const val SHORT2  = 100
    const val SHORT3  = 150
    const val SHORT4  = 200
    const val MEDIUM1 = 250
    const val MEDIUM2 = 300
    const val MEDIUM3 = 350
    const val MEDIUM4 = 400
    const val LONG1   = 450
    const val LONG2   = 500

    // ── Easing Curves ────────────────────────────────────────────────────────
    // Material 3 standard easing (cubic-bezier)
    val EmphasizedEasing      = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)  // emphasized decelerate
    val EmphasizedAccelEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f) // emphasized accelerate
    val StandardEasing        = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val StandardAccelEasing   = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)
    val StandardDecelEasing   = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)

    // ── Spring Specs ─────────────────────────────────────────────────────────
    val springSnappy = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness    = Spring.StiffnessMedium
    )
    val springSmooth = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness    = Spring.StiffnessMediumLow
    )
    val springGentle = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness    = Spring.StiffnessLow
    )

    // ── Tween Specs ──────────────────────────────────────────────────────────
    fun <T> tweenEnter(duration: Int = MEDIUM2) = tween<T>(
        durationMillis = duration,
        easing         = EmphasizedEasing
    )
    fun <T> tweenExit(duration: Int = MEDIUM1) = tween<T>(
        durationMillis = duration,
        easing         = EmphasizedAccelEasing
    )
    fun <T> tweenStandard(duration: Int = MEDIUM2) = tween<T>(
        durationMillis = duration,
        easing         = StandardEasing
    )

    // ── Screen-Level Transitions (Login ↔ Signup ↔ ForgotPassword ↔ Dashboard) ─────
    // Outgoing screen slides left and fully fades to 0; Incoming screen slides from 100% right.
    val screenEnter: EnterTransition =
        slideInHorizontally(
            initialOffsetX = { fullWidth -> fullWidth },
            animationSpec  = tween(320, easing = EmphasizedEasing)
        )

    val screenExit: ExitTransition =
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> -fullWidth / 4 },
            animationSpec = tween(320, easing = EmphasizedEasing)
        ) + fadeOut(
            animationSpec = tween(260, easing = LinearEasing),
            targetAlpha   = 0f
        )

    val screenPopEnter: EnterTransition =
        slideInHorizontally(
            initialOffsetX = { fullWidth -> -fullWidth / 4 },
            animationSpec  = tween(320, easing = EmphasizedEasing)
        ) + fadeIn(
            animationSpec = tween(260, easing = LinearEasing),
            initialAlpha  = 0f
        )

    val screenPopExit: ExitTransition =
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(320, easing = EmphasizedAccelEasing)
        )

    // ── Chat / Detail Panel Transitions (Dashboard → Chat / Profile overlay) ────────
    val chatEnter: EnterTransition =
        slideInHorizontally(
            initialOffsetX = { fullWidth -> fullWidth },
            animationSpec  = tween(300, easing = EmphasizedEasing)
        ) + fadeIn(
            animationSpec = tween(220, easing = LinearEasing),
            initialAlpha  = 0f
        )

    val chatExit: ExitTransition =
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(260, easing = EmphasizedAccelEasing)
        ) + fadeOut(
            animationSpec = tween(180, easing = LinearEasing),
            targetAlpha   = 0f
        )

    // ── Dialog / Bottom Sheet Transitions ────────────────────────────────────
    val dialogEnter: EnterTransition =
        fadeIn(tween(SHORT4, easing = EmphasizedEasing)) +
        scaleIn(
            initialScale  = 0.92f,
            animationSpec = tween(SHORT4, easing = EmphasizedEasing)
        )

    val dialogExit: ExitTransition =
        fadeOut(tween(SHORT3, easing = EmphasizedAccelEasing)) +
        scaleOut(
            targetScale   = 0.92f,
            animationSpec = tween(SHORT3, easing = EmphasizedAccelEasing)
        )

    // ── List Item Animations ──────────────────────────────────────────────────
    val itemFadeInSpec  = tween<Float>(SHORT4, easing = EmphasizedEasing)
    val itemFadeOutSpec = tween<Float>(SHORT3, easing = EmphasizedAccelEasing)
    val itemPlacementSpec = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness    = Spring.StiffnessMediumLow
    )
    // Note: animateItem placementSpec uses IntOffset internally via Compose

    // ── Visibility Toggle (inline UI elements) ───────────────────────────────
    val visibilityEnter: EnterTransition =
        fadeIn(tween(SHORT4, easing = EmphasizedEasing)) +
        expandVertically(
            expandFrom    = Alignment.Bottom,
            animationSpec = tween(SHORT4, easing = EmphasizedEasing)
        )

    val visibilityExit: ExitTransition =
        fadeOut(tween(SHORT3, easing = EmphasizedAccelEasing)) +
        shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = tween(SHORT3, easing = EmphasizedAccelEasing)
        )
}
