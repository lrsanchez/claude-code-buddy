package com.claude.buddy.ui

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha as drawAlpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claude.buddy.protocol.ChatEntry
import com.claude.buddy.protocol.Snapshot
import com.claude.buddy.state.BuddyDisplayState
import com.claude.buddy.state.BuddyUiState
import com.claude.buddy.ui.theme.*

enum class ActivePanel { CHAT, TOOLS }

@Composable
fun BuddyScreen(
    state: BuddyUiState,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Panel toggle persists across orientation changes (no Activity recreation)
    var activePanel by remember { mutableStateOf(ActivePanel.CHAT) }

    Box(modifier = Modifier.fillMaxSize().background(ClaudeBlack)) {
        if (isLandscape) {
            LandscapeLayout(state = state, activePanel = activePanel, onToggle = {
                activePanel = if (activePanel == ActivePanel.CHAT) ActivePanel.TOOLS else ActivePanel.CHAT
            })
        } else {
            PortraitLayout(state = state, activePanel = activePanel, onToggle = {
                activePanel = if (activePanel == ActivePanel.CHAT) ActivePanel.TOOLS else ActivePanel.CHAT
            })
        }
        AnimatedVisibility(
            visible = state.displayState == BuddyDisplayState.APPROVAL,
            enter = fadeIn() + scaleIn(initialScale = 0.88f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
            exit = fadeOut() + scaleOut(targetScale = 0.88f),
        ) {
            ApprovalCard(state = state, onApprove = onApprove, onDeny = onDeny)
        }
    }
}

// ── Landscape ─────────────────────────────────────────────────────────────────

@Composable
private fun LandscapeLayout(
    state: BuddyUiState,
    activePanel: ActivePanel,
    onToggle: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier.weight(0.38f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TopBar(state = state)
            Spacer(Modifier.height(12.dp))
            BuddyCharacter(displayState = state.displayState, modifier = Modifier.size(160.dp))
            Spacer(Modifier.height(8.dp))
            StateLabel(displayState = state.displayState)
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(ClaudeDivider))

        Column(
            modifier = Modifier.weight(0.62f).fillMaxHeight().padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SessionChips(snapshot = state.snapshot)
            TokenMeter(tokens = state.snapshot.tokens, tokensToday = state.snapshot.tokensToday, level = state.level)
            PanelToggle(active = activePanel, onToggle = onToggle)
            AnimatedContent(
                targetState = activePanel,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "panel",
                modifier = Modifier.weight(1f),
            ) { panel ->
                when (panel) {
                    ActivePanel.CHAT  -> ChatPanel(chat = state.snapshot.chat, modifier = Modifier.fillMaxSize())
                    ActivePanel.TOOLS -> ToolsPanel(entries = state.snapshot.entries, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

// ── Portrait ──────────────────────────────────────────────────────────────────

@Composable
private fun PortraitLayout(
    state: BuddyUiState,
    activePanel: ActivePanel,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TopBar(state = state)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BuddyCharacter(displayState = state.displayState, modifier = Modifier.size(90.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StateLabel(displayState = state.displayState)
                SessionChips(snapshot = state.snapshot)
            }
        }

        TokenMeter(tokens = state.snapshot.tokens, tokensToday = state.snapshot.tokensToday, level = state.level)

        HorizontalDivider(color = ClaudeDivider, thickness = 1.dp)

        PanelToggle(active = activePanel, onToggle = onToggle)

        AnimatedContent(
            targetState = activePanel,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "panel",
            modifier = Modifier.weight(1f),
        ) { panel ->
            when (panel) {
                ActivePanel.CHAT  -> ChatPanel(chat = state.snapshot.chat, modifier = Modifier.fillMaxSize())
                ActivePanel.TOOLS -> ToolsPanel(entries = state.snapshot.entries, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// ── Toggle ────────────────────────────────────────────────────────────────────

@Composable
private fun PanelToggle(active: ActivePanel, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ClaudeCard)
            .height(30.dp),
    ) {
        ToggleTab(label = "chat",  selected = active == ActivePanel.CHAT,  onClick = { if (active != ActivePanel.CHAT) onToggle() })
        ToggleTab(label = "tools", selected = active == ActivePanel.TOOLS, onClick = { if (active != ActivePanel.TOOLS) onToggle() })
    }
}

@Composable
private fun RowScope.ToggleTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg    by animateColorAsState(if (selected) ClaudeCoral else Color.Transparent, label = "tab_bg")
    val color by animateColorAsState(if (selected) ClaudeBlack else ClaudeTextSecondary, label = "tab_fg")
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontFamily = FontFamily.Default, color = color)
    }
}

// ── TopBar ────────────────────────────────────────────────────────────────────

@Composable
fun TopBar(state: BuddyUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectionDot(connected = state.isConnected)
            Text(
                text = if (state.ownerName.isNotEmpty()) "Hey ${state.ownerName}" else "Claude Buddy",
                style = MaterialTheme.typography.labelSmall,
                color = ClaudeTextSecondary,
            )
        }
        Text(text = state.deviceName, style = MaterialTheme.typography.labelSmall, color = ClaudeCoralDim)
    }
}

@Composable
private fun ConnectionDot(connected: Boolean) {
    val inf = rememberInfiniteTransition(label = "dot")
    val scale by inf.animateFloat(
        initialValue = 0.8f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .scale(if (connected) scale else 1f)
            .clip(CircleShape)
            .background(if (connected) ClaudeGreen else ClaudeTextSecondary)
    )
}

// ── Buddy character ───────────────────────────────────────────────────────────

@Composable
fun BuddyCharacter(displayState: BuddyDisplayState, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "buddy")
    val bob by inf.animateFloat(
        initialValue = -4f, targetValue = 4f,
        animationSpec = infiniteRepeatable(
            tween(when (displayState) { BuddyDisplayState.SLEEP -> 3000; BuddyDisplayState.BUSY -> 600; else -> 1500 }, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "bob",
    )
    var eyeOpen by remember { mutableStateOf(true) }
    LaunchedEffect(displayState) {
        while (true) {
            kotlinx.coroutines.delay(when (displayState) {
                BuddyDisplayState.SLEEP -> 500L; BuddyDisplayState.ATTENTION -> 300L
                else -> (2000..5000).random().toLong()
            })
            eyeOpen = false; kotlinx.coroutines.delay(120); eyeOpen = true
        }
    }
    val bodyColor by animateColorAsState(
        when (displayState) {
            BuddyDisplayState.SLEEP     -> ClaudeTextSecondary.copy(alpha = 0.3f)
            BuddyDisplayState.IDLE      -> ClaudeCoral.copy(alpha = 0.8f)
            BuddyDisplayState.BUSY      -> ClaudeTeal.copy(alpha = 0.9f)
            BuddyDisplayState.ATTENTION -> ClaudeAmber
            BuddyDisplayState.CELEBRATE -> ClaudeGreen
            BuddyDisplayState.APPROVAL  -> ClaudeAmber
        }, tween(500), label = "body",
    )
    val glowAlpha by inf.animateFloat(
        0.2f, 0.6f,
        infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    val showGlow = displayState == BuddyDisplayState.ATTENTION || displayState == BuddyDisplayState.APPROVAL

    Canvas(modifier = modifier.offset(y = bob.dp)) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val r  = minOf(size.width, size.height) * 0.38f
        if (showGlow) drawCircle(ClaudeAmber.copy(alpha = glowAlpha), r * 1.35f, Offset(cx, cy))
        drawCircle(bodyColor, r, Offset(cx, cy))
        val eyeY = cy - r * 0.15f; val eyeSp = r * 0.38f; val eyeR = if (eyeOpen) r * 0.13f else r * 0.025f
        val ec = if (displayState == BuddyDisplayState.SLEEP) ClaudeBlack.copy(alpha = 0.4f) else ClaudeBlack
        listOf(-eyeSp, eyeSp).forEach { xOff ->
            if (eyeOpen) drawCircle(ec, eyeR, Offset(cx + xOff, eyeY))
            else drawLine(ec, Offset(cx + xOff - eyeR, eyeY), Offset(cx + xOff + eyeR, eyeY), 3f, cap = StrokeCap.Round)
        }
        if (displayState == BuddyDisplayState.BUSY)
            drawArc(ClaudeBlack.copy(alpha = 0.3f), 160f, 40f, false, Offset(cx - r * 0.4f, cy + r * 0.1f), Size(r * 0.8f, r * 0.4f), style = Stroke(3f, cap = StrokeCap.Round))
    }
}

@Composable
fun StateLabel(displayState: BuddyDisplayState, modifier: Modifier = Modifier) {
    val (label, color) = when (displayState) {
        BuddyDisplayState.SLEEP     -> "sleeping"          to ClaudeTextSecondary
        BuddyDisplayState.IDLE      -> "idle"              to ClaudeTextSecondary
        BuddyDisplayState.BUSY      -> "working"           to ClaudeTeal
        BuddyDisplayState.ATTENTION -> "needs you"         to ClaudeAmber
        BuddyDisplayState.CELEBRATE -> "level up!"         to ClaudeGreen
        BuddyDisplayState.APPROVAL  -> "awaiting approval" to ClaudeAmber
    }
    Text(label, style = MaterialTheme.typography.bodyLarge, color = color, modifier = modifier)
}

// ── Session chips ─────────────────────────────────────────────────────────────

@Composable
fun SessionChips(snapshot: Snapshot) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("sessions", "${snapshot.total}",   ClaudeTextSecondary)
        Chip("running",  "${snapshot.running}", if (snapshot.running > 0) ClaudeTeal else ClaudeTextSecondary, snapshot.running > 0)
        Chip("waiting",  "${snapshot.waiting}", if (snapshot.waiting > 0) ClaudeAmber else ClaudeTextSecondary, snapshot.waiting > 0)
    }
}

@Composable
private fun Chip(label: String, value: String, color: Color, pulse: Boolean = false) {
    val inf = rememberInfiniteTransition(label = "chip_$label")
    val alpha by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
    Surface(
        shape = RoundedCornerShape(20.dp), color = ClaudeCard,
        modifier = Modifier.drawAlpha(if (pulse) alpha else 1f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ── Token meter ───────────────────────────────────────────────────────────────

@Composable
fun TokenMeter(tokens: Long, tokensToday: Long, level: Int) {
    val prog = (tokensToday % 50_000) / 50_000f
    val animProg by animateFloatAsState(prog, tween(800), label = "arc")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(44.dp)) {
                val s = 4.dp.toPx()
                drawArc(ClaudeCard, -90f, 360f, false, style = Stroke(s, cap = StrokeCap.Round))
                drawArc(ClaudeCoral, -90f, animProg * 360f, false, style = Stroke(s, cap = StrokeCap.Round))
            }
            Text("L$level", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = ClaudeCoral)
        }
        Column {
            Text(formatTokens(tokensToday), fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = ClaudeTextPrimary)
            Text("today  ·  ${formatTokens(tokens)} total", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ── Chat panel ────────────────────────────────────────────────────────────────

@Composable
fun ChatPanel(chat: List<ChatEntry>, modifier: Modifier = Modifier) {
    val reversed = remember(chat) { chat.reversed() }
    val listState = rememberLazyListState()
    LaunchedEffect(reversed.size) {
        if (reversed.isNotEmpty()) listState.animateScrollToItem(reversed.size - 1)
    }
    Column(modifier = modifier) {
        if (reversed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Conversation will appear here\nonce Claude Code is active",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ClaudeTextSecondary.copy(alpha = 0.35f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(reversed, key = { index, _ -> index }) { _, entry ->
                    ChatBubble(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    val isUser = entry.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd   = if (isUser) 4.dp  else 14.dp,
            ),
            color = if (isUser) ClaudeCoral.copy(alpha = 0.25f) else ClaudeCard,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isUser) "you" else "claude",
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = if (isUser) ClaudeCoral else ClaudeTextSecondary,
                    )
                    if (entry.session.isNotEmpty()) {
                        Text(
                            text = "[${entry.session}]",
                            fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                            color = ClaudeCoralDim,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(entry.text, style = MaterialTheme.typography.bodySmall, color = ClaudeTextPrimary)
            }
        }
    }
}

// ── Tools panel ───────────────────────────────────────────────────────────────

@Composable
fun ToolsPanel(entries: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Tool calls will appear here",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ClaudeTextSecondary.copy(alpha = 0.35f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries, key = { it }) { entry ->
                    AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically { -it }) {
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Approval card ─────────────────────────────────────────────────────────────

@Composable
private fun ApprovalCard(state: BuddyUiState, onApprove: () -> Unit, onDeny: () -> Unit) {
    val prompt = state.snapshot.prompt ?: return
    Box(
        modifier = Modifier.fillMaxSize().background(ClaudeBlack.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp), color = ClaudeCard,
            modifier = Modifier.width(380.dp), tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Permission Request", style = MaterialTheme.typography.headlineLarge, color = ClaudeAmber)
                Surface(shape = RoundedCornerShape(8.dp), color = ClaudeBlack) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(prompt.tool, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = ClaudeCoral)
                        if (prompt.hint.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(prompt.hint, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = ClaudeTextPrimary)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDeny, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ClaudeRed)) {
                        Text("Deny", color = Color.White)
                    }
                    Button(onClick = onApprove, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ClaudeGreen)) {
                        Text("Approve", color = Color.White)
                    }
                }
            }
        }
    }
}

private fun formatTokens(n: Long) = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
    n >= 1_000     -> "${"%.1f".format(n / 1_000.0)}K"
    else           -> "$n"
}
