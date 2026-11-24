package cc.pulsez.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.pulsez.app.core.CharacterAwakeningManager
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal const val AWAKENING_BANNER_TEST_TAG = "awakening-top-banner"
internal const val AWAKENING_WHISPER_TEXT_TAG = "awakening-whisper-text"
internal const val REUNION_OVERLAY_TEST_TAG = "pulsar-reunion-overlay"
internal const val REUNION_SKIP_BUTTON_TEST_TAG = "pulsar-reunion-skip"
internal const val REUNION_ENTER_BUTTON_TEST_TAG = "pulsar-reunion-enter"

private val DeepSpaceCardBg = Color(0xFF070810)
private val SpaceCanvasBg = Color(0xFF020204)
private val StarTint = Color(0xFFDCE4FF)
private val PulsarCoreMid = Color(0xFFEAF2FF)
private val PulsarBeamBlue = Color(0xFF8EACFF)
private val PulsarHaloIndigo = Color(0xFF6D64FF)
private val NebulaPrimary = Color(0xFF5058FF)
private val NebulaSecondary = Color(0xFFAD46FF)
private val NebulaCyan = Color(0xFF32BEFF)
private val ReunionInk = Color(0xFF09090B)
private val ReunionButtonInk = Color(0xFF0A0A0C)

private const val WHISPER_FADE_IN_MS = 280
private const val WHISPER_STAY_MS = 2_400L
private const val WHISPER_FADE_OUT_MS = 300
private const val REUNION_SETTLE_MS = 2_550
private const val REUNION_ENTER_START_MS = 2_210f
private const val REUNION_ENTER_DURATION_MS = 340f
private const val REUNION_EXIT_FADE_MS = 480
private const val REUNION_REDUCED_ENTER_MS = 220
private const val REUNION_REDUCED_EXIT_MS = 220

/**
 * 顶栏下方的深空回忆浮窗：
 * - `WHISPERS`：微缩 -12° 呼吸脉冲星 + 逐条淡入/停留/上浮消散的 TA 第一人称独白；
 * - `FAILED`：异常逃生门，给出「再试一次」与「先直接开聊」（≥ 48dp）。
 */
@Composable
fun AwakeningTopBanner(
    state: AwakeningUiState,
    reduceMotion: Boolean,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onStartReunion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.phase != AwakeningUiPhase.WHISPERS && state.phase != AwakeningUiPhase.FAILED) return

    Surface(
        color = DeepSpaceCardBg,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, PulsarBeamBlue.copy(alpha = 0.24f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag(AWAKENING_BANNER_TEST_TAG),
    ) {
        if (state.phase == AwakeningUiPhase.FAILED) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MiniPulsarIndicator(animate = !reduceMotion)
                    Text(
                        text = state.errorMessage ?: CharacterAwakeningManager.FAILURE_COPY,
                        style = MaterialTheme.typography.bodyMedium,
                        color = StarTint,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = "先直接开聊",
                            style = MaterialTheme.typography.labelLarge,
                            color = StarTint.copy(alpha = 0.78f),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = "再试一次",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                }
            }
        } else {
            WhisperSequenceRow(
                whispers = state.whispers,
                streamCompleted = state.streamCompleted,
                reduceMotion = reduceMotion,
                onAllWhispersDone = onStartReunion,
            )
        }
    }
}

@Composable
private fun WhisperSequenceRow(
    whispers: List<String>,
    streamCompleted: Boolean,
    reduceMotion: Boolean,
    onAllWhispersDone: () -> Unit,
) {
    val currentWhispers by rememberUpdatedState(whispers)
    val currentStreamCompleted by rememberUpdatedState(streamCompleted)
    val currentOnAllDone by rememberUpdatedState(onAllWhispersDone)
    val accessibilityManager = LocalAccessibilityManager.current
    val stayMillis = remember(accessibilityManager) {
        accessibilityManager?.calculateRecommendedTimeoutMillis(
            WHISPER_STAY_MS,
            false,
            true,
            true,
        ) ?: WHISPER_STAY_MS
    }
    val density = LocalDensity.current
    val exitShiftPx = with(density) { 10.dp.toPx() }
    val enterShiftPx = with(density) { 6.dp.toPx() }

    var activeIndex by remember { mutableIntStateOf(0) }
    var displayText by remember { mutableStateOf("TA 正在从深空信号里寻找你们的回忆……") }
    val alphaAnim = remember { Animatable(1f) }
    val translateYAnim = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            if (activeIndex < currentWhispers.size) {
                val whisper = currentWhispers[activeIndex]
                displayText = whisper
                if (reduceMotion) {
                    alphaAnim.snapTo(1f)
                    translateYAnim.snapTo(0f)
                    delay(stayMillis)
                } else {
                    alphaAnim.snapTo(0f)
                    translateYAnim.snapTo(enterShiftPx)
                    alphaAnim.animateTo(1f, tween(WHISPER_FADE_IN_MS, easing = FastOutSlowInEasing))
                    translateYAnim.snapTo(0f)
                    delay(stayMillis)
                    // 向上轻柔渐消 300ms
                    translateYAnim.snapTo(0f)
                    alphaAnim.animateTo(0f, tween(WHISPER_FADE_OUT_MS, easing = FastOutSlowInEasing))
                    translateYAnim.snapTo(-exitShiftPx)
                }
                activeIndex += 1
            } else if (currentStreamCompleted) {
                currentOnAllDone()
                break
            } else {
                // 尚未播到新一句时保持温和等待状态，直到流里吐出下一句或流结束。
                if (activeIndex == 0) {
                    displayText = "TA 正在从深空信号里寻找你们的回忆……"
                    alphaAnim.snapTo(1f)
                    translateYAnim.snapTo(0f)
                }
                snapshotFlow { currentWhispers.size to currentStreamCompleted }
                    .first { (size, done) -> size > activeIndex || done }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MiniPulsarIndicator(animate = !reduceMotion)
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyMedium,
            color = StarTint,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    alpha = alphaAnim.value
                    translationY = translateYAnim.value
                }
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(AWAKENING_WHISPER_TEXT_TAG),
        )
    }
}

@Composable
private fun MiniPulsarIndicator(animate: Boolean) {
    val pulse = if (animate) {
        val transition = rememberInfiniteTransition(label = "mini-pulsar")
        transition.animateFloat(
            initialValue = 0.78f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1550, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "mini-pulsar-scale",
        ).value
    } else {
        1f
    }
    Canvas(
        modifier = Modifier
            .size(22.dp)
            .semantics { contentDescription = "脉冲星信号" },
    ) {
        val r = (size.minDimension / 2f) * pulse
        drawPulsarCoreAndBeams(center = center, baseRadius = r * 0.55f, alpha = 1f)
    }
}

/**
 * 致敬 `index (5).html` 的全屏脉冲星白场「我们终于再会」重逢仪式浮层：
 * - 0.0s – 1.55s：#020204 深空星群 + 靛紫星云 + -12° 倾角蓝白脉冲星加速逼近并漫溢全屏；
 * - 1.32s – 2.55s：纯白场 #FFFFFF + 墨黑 #09090B「我们终于再会」与副标题（延迟 280ms 轻柔上浮）+「进入聊天」按钮淡入上浮；
 * - ≥ 2.55s：定格停留在纯白场等待用户点击「进入聊天」，点击后 480ms 柔和退去并回调 [onComplete]；
 * - reduceMotion：跳过急速逼近与强闪白，220ms 淡入纯白场与「进入聊天」按钮后同样停驻等待点击；
 * - 白场未定格且「进入聊天」未出现前右上角提供 ≥ 48dp「跳过」按钮，进入定格或退场后立即移除；
 * - 浮层展示期间通过内层 `BackHandler` 拦截系统返回，直接走同一退场完成重逢，不退出房间。
 * 依据：` WP-B、`ChatScreen.kt:973`、`AppRoot.kt:415`
 */
@Composable
fun PulsarReunionOverlay(
    characterName: String,
    subtitle: String,
    reduceMotion: Boolean,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnComplete by rememberUpdatedState(onComplete)
    var completed by remember { mutableStateOf(false) }
    var isExiting by remember { mutableStateOf(false) }

    val triggerSkipImmediate: () -> Unit = {
        if (!completed && !isExiting) {
            isExiting = true
            completed = true
            currentOnComplete()
        }
    }
    val startExit: () -> Unit = {
        if (!completed && !isExiting) {
            isExiting = true
        }
    }

    // 依据：ThreadsScreen.kt:269-271 & AppRoot.kt:415 — 后组合的启用 BackHandler 优先于外层 PredictiveBackHandler，
    // 在浮层展示或白场定格期间按系统返回时拦截并不退出房间，走同一防重入退场完成重逢。
    BackHandler(enabled = true) {
        startExit()
    }

    val safeName = characterName.trim().ifBlank { "TA" }
    val safeSubtitle = subtitle.trim().ifBlank { CharacterAwakeningManager.DEFAULT_REUNION_SUBTITLE }
    val subtitleLine = "$safeName · $safeSubtitle"

    val density = LocalDensity.current
    val subtitleShiftPx = with(density) { 10.dp.toPx() }
    val enterShiftPx = with(density) { 12.dp.toPx() }
    val stars = remember { generateStarField(count = 120) }

    val timelineMs = remember { Animatable(0f) }
    val reducedAlpha = remember { Animatable(0f) }
    val exitAlpha = remember { Animatable(1f) }
    val enterFocusRequester = remember { FocusRequester() }

    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            reducedAlpha.snapTo(0f)
            reducedAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = REUNION_REDUCED_ENTER_MS, easing = FastOutSlowInEasing),
            )
        } else {
            timelineMs.snapTo(0f)
            timelineMs.animateTo(
                targetValue = REUNION_SETTLE_MS.toFloat(),
                animationSpec = tween(durationMillis = REUNION_SETTLE_MS, easing = LinearEasing),
            )
        }
    }

    LaunchedEffect(isExiting) {
        if (isExiting && !completed) {
            if (reduceMotion) {
                reducedAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = REUNION_REDUCED_EXIT_MS, easing = FastOutSlowInEasing),
                )
            } else {
                exitAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = REUNION_EXIT_FADE_MS, easing = FastOutSlowInEasing),
                )
            }
            if (!completed) {
                completed = true
                currentOnComplete()
            }
        }
    }

    val t = timelineMs.value
    val overlayAlpha = if (reduceMotion) {
        reducedAlpha.value
    } else {
        exitAlpha.value
    }

    val whiteProgress = if (reduceMotion) {
        1f
    } else {
        ((t - 1_320f) / 230f).coerceIn(0f, 1f)
    }

    val titleAlpha = if (reduceMotion) {
        1f
    } else {
        ((t - 1_550f) / 320f).coerceIn(0f, 1f)
    }

    val subProgress = if (reduceMotion) {
        1f
    } else {
        // 主标题出现后再延迟 280ms 轻柔上浮
        ((t - 1_830f) / 380f).coerceIn(0f, 1f)
    }

    val showEnterButton = reduceMotion || t >= REUNION_ENTER_START_MS
    val enterProgress = if (reduceMotion) {
        1f
    } else {
        ((t - REUNION_ENTER_START_MS) / REUNION_ENTER_DURATION_MS).coerceIn(0f, 1f)
    }
    val showSkipButton = !reduceMotion && t < REUNION_ENTER_START_MS && !isExiting && !completed
    val enterReady = if (reduceMotion) {
        reducedAlpha.value >= 1f && !isExiting && !completed
    } else {
        t >= REUNION_SETTLE_MS.toFloat() && !isExiting && !completed
    }

    LaunchedEffect(enterReady) {
        if (enterReady) {
            runCatching { enterFocusRequester.requestFocus() }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .testTag(REUNION_OVERLAY_TEST_TAG),
    ) {
        if (reduceMotion) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 1) 深空宇宙底色
                drawRect(color = SpaceCanvasBg)

                val spaceVisibility = (1f - whiteProgress).coerceIn(0f, 1f)
                if (spaceVisibility > 0.01f) {
                    // 2) 柔和靛紫与青蓝星云微晕
                    drawNebula(spaceVisibility)
                    // 3) 闪烁冷白蓝星群 Color(0xFFDCE4FF)
                    drawTwinklingStars(stars, t, spaceVisibility)
                    // 4) -12° 倾角蓝白脉冲星伴随心跳由 scale=0.08f 冲向 scale=16f
                    val approachFraction = (t / 1_550f).coerceIn(0f, 1f)
                    val easedApproach = cubicEaseOutDramatic(approachFraction)
                    val pulsarScale = 0.08f + (16f - 0.08f) * easedApproach
                    val heartbeat = 1f + 0.16f * sin((t / 1_550f) * 2f * PI.toFloat() * 1.4f)
                    val baseR = (size.minDimension * 0.11f) * pulsarScale * heartbeat
                    drawPulsarCoreAndBeams(
                        center = center,
                        baseRadius = baseR,
                        alpha = spaceVisibility,
                    )
                }

                // 5) 纯白场转换 (#FFFFFF)
                if (whiteProgress > 0f) {
                    drawRect(color = Color.White.copy(alpha = whiteProgress))
                }
            }
        }

        // 墨黑字卡 #09090B + 定格「进入聊天」胶囊按钮
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "我们终于再会",
                    color = ReunionInk,
                    fontSize = 36.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.2).sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer { alpha = titleAlpha },
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = subtitleLine,
                    color = ReunionInk.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.titleSmall,
                    letterSpacing = 0.8.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        alpha = subProgress
                        translationY = subtitleShiftPx * (1f - subProgress)
                    },
                )
            }

            Spacer(Modifier.height(36.dp))

            // 预留 50dp 高度防止 2210ms 插入按钮时主副标题垂直跳动
            Box(
                modifier = Modifier.heightIn(min = 50.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showEnterButton) {
                    OutlinedButton(
                        onClick = startExit,
                        enabled = !isExiting && !completed,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, ReunionButtonInk),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White,
                            contentColor = ReunionButtonInk,
                            disabledContainerColor = Color.White,
                            disabledContentColor = ReunionButtonInk.copy(alpha = 0.5f),
                        ),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
                        modifier = Modifier
                            .heightIn(min = 50.dp)
                            .focusRequester(enterFocusRequester)
                            .graphicsLayer {
                                alpha = enterProgress
                                translationY = enterShiftPx * (1f - enterProgress)
                            }
                            .testTag(REUNION_ENTER_BUTTON_TEST_TAG),
                    ) {
                        Text(
                            text = "进入聊天",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.5.sp,
                            ),
                        )
                    }
                }
            }
        }

        // 右上角 ≥ 48dp「跳过」按钮：仅在白场未定格且「进入聊天」未出现前条件渲染
        if (showSkipButton) {
            val skipColor = if (whiteProgress > 0.5f) {
                ReunionInk.copy(alpha = 0.48f)
            } else {
                Color.White.copy(alpha = 0.72f)
            }
            TextButton(
                onClick = triggerSkipImmediate,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(top = 8.dp, end = 12.dp)
                    .widthIn(min = 48.dp)
                    .heightIn(min = 48.dp)
                    .testTag(REUNION_SKIP_BUTTON_TEST_TAG),
            ) {
                Text(
                    text = "跳过",
                    style = MaterialTheme.typography.labelLarge,
                    color = skipColor,
                )
            }
        }
    }
}

private data class StarSpec(
    val xFraction: Float,
    val yFraction: Float,
    val radiusDp: Float,
    val baseAlpha: Float,
    val speed: Float,
    val phase: Float,
)

private fun generateStarField(count: Int): List<StarSpec> {
    var seed = 0x5A17_93E1L
    fun nextFloat(): Float {
        seed = (seed * 6364136223846793005L + 1442695040888963407L)
        return ((seed ushr 40).toInt() and 0xFFFFFF) / 16777216f
    }
    return List(count) {
        StarSpec(
            xFraction = nextFloat(),
            yFraction = nextFloat(),
            radiusDp = 0.5f + nextFloat() * 1.4f,
            baseAlpha = 0.25f + nextFloat() * 0.65f,
            speed = 0.002f + nextFloat() * 0.005f,
            phase = nextFloat() * 2f * PI.toFloat(),
        )
    }
}

private fun DrawScope.drawNebula(alphaScale: Float) {
    val maxDim = size.maxDimension
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                NebulaPrimary.copy(alpha = 0.16f * alphaScale),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.50f, size.height * 0.48f),
            radius = maxDim * 0.38f,
        ),
        center = Offset(size.width * 0.50f, size.height * 0.48f),
        radius = maxDim * 0.38f,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                NebulaSecondary.copy(alpha = 0.09f * alphaScale),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.40f, size.height * 0.56f),
            radius = maxDim * 0.45f,
        ),
        center = Offset(size.width * 0.40f, size.height * 0.56f),
        radius = maxDim * 0.45f,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                NebulaCyan.copy(alpha = 0.07f * alphaScale),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.62f, size.height * 0.42f),
            radius = maxDim * 0.42f,
        ),
        center = Offset(size.width * 0.62f, size.height * 0.42f),
        radius = maxDim * 0.42f,
    )
}

private fun DrawScope.drawTwinklingStars(
    stars: List<StarSpec>,
    elapsedMs: Float,
    alphaScale: Float,
) {
    stars.forEach { star ->
        val twinkle = 0.5f + 0.5f * sin(elapsedMs * star.speed + star.phase)
        val alpha = (star.baseAlpha * (0.55f + twinkle * 0.45f) * alphaScale).coerceIn(0f, 1f)
        val r = star.radiusDp.dp.toPx() * (0.85f + twinkle * 0.25f)
        drawCircle(
            color = StarTint.copy(alpha = alpha),
            radius = r,
            center = Offset(star.xFraction * size.width, star.yFraction * size.height),
        )
    }
}

private fun DrawScope.drawPulsarCoreAndBeams(
    center: Offset,
    baseRadius: Float,
    alpha: Float,
) {
    val safeRadius = baseRadius.coerceAtLeast(1f)
    val haloRadius = (safeRadius * 2.2f).coerceAtMost(size.maxDimension * 3f)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to Color.White.copy(alpha = alpha),
                0.10f to PulsarCoreMid.copy(alpha = 0.95f * alpha),
                0.22f to PulsarBeamBlue.copy(alpha = 0.85f * alpha),
                0.45f to PulsarHaloIndigo.copy(alpha = 0.30f * alpha),
                1.00f to Color.Transparent,
            ),
            center = center,
            radius = haloRadius,
        ),
        center = center,
        radius = haloRadius,
    )

    // -12° 倾角修长星芒光束（柔光主束 + 锐利细线）
    rotate(degrees = -12f, pivot = center) {
        val softHalfLen = (safeRadius * 2.4f).coerceAtMost(size.maxDimension * 2.2f)
        val softBrush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                PulsarBeamBlue.copy(alpha = 0.16f * alpha),
                Color.White.copy(alpha = 0.95f * alpha),
                PulsarBeamBlue.copy(alpha = 0.16f * alpha),
                Color.Transparent,
            ),
            startX = center.x - softHalfLen,
            endX = center.x + softHalfLen,
        )
        drawLine(
            brush = softBrush,
            start = Offset(center.x - softHalfLen, center.y),
            end = Offset(center.x + softHalfLen, center.y),
            strokeWidth = (safeRadius * 0.16f).coerceIn(1.5f, 36f),
            cap = StrokeCap.Round,
        )

        val sharpHalfLen = (safeRadius * 3.8f).coerceAtMost(size.maxDimension * 2.6f)
        val sharpBrush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                PulsarBeamBlue.copy(alpha = 0.65f * alpha),
                Color.White.copy(alpha = alpha),
                PulsarBeamBlue.copy(alpha = 0.65f * alpha),
                Color.Transparent,
            ),
            startX = center.x - sharpHalfLen,
            endX = center.x + sharpHalfLen,
        )
        drawLine(
            brush = sharpBrush,
            start = Offset(center.x - sharpHalfLen, center.y),
            end = Offset(center.x + sharpHalfLen, center.y),
            strokeWidth = (safeRadius * 0.04f).coerceIn(1f, 6f),
            cap = StrokeCap.Round,
        )
    }
}

private fun cubicEaseOutDramatic(fraction: Float): Float {
    val x = fraction.coerceIn(0f, 1f)
    // 前半程缓慢接近，后程加速贴脸漫溢全屏（致敬 index (5).html approach 曲线）
    return (0.12f * x) + (0.88f * x.pow(3.6f))
}
