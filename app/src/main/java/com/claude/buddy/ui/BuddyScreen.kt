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
    onToggleTheme: () -> Unit = {},
    onReconnect: () -> Unit = {},
) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val C = LocalBuddyColors.current
    var activePanel by remember { mutableStateOf(ActivePanel.CHAT) }
    val onPanelToggle = { activePanel = if (activePanel == ActivePanel.CHAT) ActivePanel.TOOLS else ActivePanel.CHAT }

    Box(modifier = Modifier.fillMaxSize().background(C.background)) {
        if (isLandscape) {
            LandscapeLayout(state, C, activePanel, onToggleTheme, onReconnect, onPanelToggle)
        } else {
            PortraitLayout(state, C, activePanel, onToggleTheme, onReconnect, onPanelToggle)
        }
        AnimatedVisibility(
            visible = state.displayState == BuddyDisplayState.APPROVAL,
            enter = fadeIn() + scaleIn(initialScale = 0.88f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
            exit  = fadeOut() + scaleOut(targetScale = 0.88f),
        ) {
            ApprovalCard(state, C, onApprove, onDeny)
        }
    }
}

// ── Landscape ─────────────────────────────────────────────────────────────────

@Composable
private fun LandscapeLayout(
    state: BuddyUiState, C: BuddyColors,
    activePanel: ActivePanel, onToggleTheme: () -> Unit, onReconnect: () -> Unit, onPanel: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier.weight(0.38f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TopBar(state, C, onToggleTheme)
            Spacer(Modifier.height(12.dp))
            BuddyCharacter(state.displayState, C, modifier = Modifier.size(160.dp))
            Spacer(Modifier.height(8.dp))
            StateLabel(state.displayState, C)
            if (state.displayState == BuddyDisplayState.SLEEP || state.displayState == BuddyDisplayState.SEARCHING) {
                Spacer(Modifier.height(12.dp))
                ConnectButton(C, onReconnect)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(C.divider))
        Column(
            modifier = Modifier.weight(0.62f).fillMaxHeight().padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SessionChips(state.snapshot, C)
            TokenMeter(state.snapshot.tokens, state.snapshot.tokensToday, state.level, C)
            PanelToggle(activePanel, C, onPanel)
            AnimatedContent(targetState = activePanel,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "panel", modifier = Modifier.weight(1f)) { panel ->
                when (panel) {
                    ActivePanel.CHAT  -> ChatPanel(state.snapshot.chat, C, Modifier.fillMaxSize())
                    ActivePanel.TOOLS -> ToolsPanel(state.snapshot.entries, C, Modifier.fillMaxSize())
                }
            }
        }
    }
}

// ── Portrait ──────────────────────────────────────────────────────────────────

@Composable
private fun PortraitLayout(
    state: BuddyUiState, C: BuddyColors,
    activePanel: ActivePanel, onToggleTheme: () -> Unit, onReconnect: () -> Unit, onPanel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TopBar(state, C, onToggleTheme)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BuddyCharacter(state.displayState, C, modifier = Modifier.size(90.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StateLabel(state.displayState, C)
                if (state.displayState == BuddyDisplayState.SLEEP || state.displayState == BuddyDisplayState.SEARCHING) {
                    ConnectButton(C, onReconnect)
                } else {
                    SessionChips(state.snapshot, C)
                }
            }
        }
        TokenMeter(state.snapshot.tokens, state.snapshot.tokensToday, state.level, C)
        HorizontalDivider(color = C.divider, thickness = 1.dp)
        PanelToggle(activePanel, C, onPanel)
        AnimatedContent(targetState = activePanel,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "panel", modifier = Modifier.weight(1f)) { panel ->
            when (panel) {
                ActivePanel.CHAT  -> ChatPanel(state.snapshot.chat, C, Modifier.fillMaxSize())
                ActivePanel.TOOLS -> ToolsPanel(state.snapshot.entries, C, Modifier.fillMaxSize())
            }
        }
    }
}

// ── Connect button ────────────────────────────────────────────────────────────

@Composable
fun ConnectButton(C: BuddyColors = LocalBuddyColors.current, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = androidx.compose.foundation.BorderStroke(1.dp, C.coral),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text("Connect", fontSize = 12.sp, color = C.coral)
    }
}

// ── TopBar ────────────────────────────────────────────────────────────────────

@Composable
fun TopBar(state: BuddyUiState, C: BuddyColors = LocalBuddyColors.current, onToggleTheme: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectionDot(state.isConnected, C)
            Text(
                text = if (state.ownerName.isNotEmpty()) "Hey ${state.ownerName}" else "Claude Buddy",
                style = MaterialTheme.typography.labelSmall,
                color = C.textSecondary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = state.deviceName, style = MaterialTheme.typography.labelSmall, color = C.coralDim)
            // Theme toggle: sun for light mode, moon for dark
            Text(
                text = if (C.isLight) "☾" else "☀",
                fontSize = 14.sp,
                color = C.textSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onToggleTheme)
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun ConnectionDot(connected: Boolean, C: BuddyColors) {
    val inf = rememberInfiniteTransition(label = "dot")
    val scale by inf.animateFloat(
        0.8f, 1.2f,
        infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .scale(if (connected) scale else 1f)
            .clip(CircleShape)
            .background(if (connected) C.green else C.textSecondary)
    )
}

// ── Panel toggle ──────────────────────────────────────────────────────────────

@Composable
private fun PanelToggle(active: ActivePanel, C: BuddyColors, onToggle: () -> Unit) {
    Row(modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(C.card).height(30.dp)) {
        ToggleTab("chat",  active == ActivePanel.CHAT,  C) { if (active != ActivePanel.CHAT) onToggle() }
        ToggleTab("tools", active == ActivePanel.TOOLS, C) { if (active != ActivePanel.TOOLS) onToggle() }
    }
}

@Composable
private fun RowScope.ToggleTab(label: String, selected: Boolean, C: BuddyColors, onClick: () -> Unit) {
    val bg    by animateColorAsState(if (selected) C.coral else Color.Transparent, label = "tab_bg")
    val color by animateColorAsState(if (selected) C.onCoral else C.textSecondary, label = "tab_fg")
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, color = color)
    }
}

// ── Buddy character ───────────────────────────────────────────────────────────

@Composable
fun BuddyCharacter(displayState: BuddyDisplayState, C: BuddyColors = LocalBuddyColors.current, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "buddy")
    // Reduce animation speed in light mode (e-ink friendly)
    val bobDuration = if (C.isLight) when (displayState) {
        BuddyDisplayState.SLEEP -> 5000; BuddyDisplayState.BUSY -> 1200; else -> 3000
    } else when (displayState) {
        BuddyDisplayState.SLEEP -> 3000; BuddyDisplayState.BUSY -> 600; else -> 1500
    }
    val bob by inf.animateFloat(
        -4f, 4f,
        infiniteRepeatable(tween(bobDuration, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    var eyeOpen by remember { mutableStateOf(true) }
    LaunchedEffect(displayState) {
        while (true) {
            kotlinx.coroutines.delay(
                when (displayState) {
                    BuddyDisplayState.SLEEP -> 500L; BuddyDisplayState.ATTENTION -> 300L
                    else -> (2000..5000).random().toLong()
                }
            )
            eyeOpen = false; kotlinx.coroutines.delay(120); eyeOpen = true
        }
    }
    val bodyColor by animateColorAsState(
        when (displayState) {
            BuddyDisplayState.SLEEP     -> C.textSecondary.copy(alpha = 0.3f)
            BuddyDisplayState.SEARCHING -> C.teal.copy(alpha = 0.4f)
            BuddyDisplayState.IDLE      -> C.coral.copy(alpha = 0.8f)
            BuddyDisplayState.BUSY      -> C.teal.copy(alpha = 0.9f)
            BuddyDisplayState.ATTENTION -> C.amber
            BuddyDisplayState.CELEBRATE -> C.green
            BuddyDisplayState.APPROVAL  -> C.amber
        }, tween(500), label = "body",
    )
    val glowAlpha by inf.animateFloat(
        0.2f, 0.6f,
        infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    val showGlow = !C.isLight && (displayState == BuddyDisplayState.ATTENTION || displayState == BuddyDisplayState.APPROVAL)

    Canvas(modifier = modifier.offset(y = bob.dp)) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val r  = minOf(size.width, size.height) * 0.38f
        if (showGlow) drawCircle(C.amber.copy(alpha = glowAlpha), r * 1.35f, Offset(cx, cy))
        drawCircle(bodyColor, r, Offset(cx, cy))
        // In light mode draw a thin border so the character is visible on white
        if (C.isLight) drawCircle(C.divider, r, Offset(cx, cy), style = Stroke(2f))
        val eyeY = cy - r * 0.15f; val eyeSp = r * 0.38f
        val eyeR = if (eyeOpen) r * 0.13f else r * 0.025f
        val ec = if (C.isLight) C.background else C.background
        listOf(-eyeSp, eyeSp).forEach { xOff ->
            if (eyeOpen) drawCircle(ec, eyeR, Offset(cx + xOff, eyeY))
            else drawLine(ec, Offset(cx + xOff - eyeR, eyeY), Offset(cx + xOff + eyeR, eyeY), 3f, cap = StrokeCap.Round)
        }
        if (displayState == BuddyDisplayState.BUSY)
            drawArc(ec.copy(alpha = 0.4f), 160f, 40f, false, Offset(cx - r * 0.4f, cy + r * 0.1f), Size(r * 0.8f, r * 0.4f), style = Stroke(3f, cap = StrokeCap.Round))
    }
}

@Composable
fun StateLabel(displayState: BuddyDisplayState, C: BuddyColors = LocalBuddyColors.current, modifier: Modifier = Modifier) {
    val (label, color) = when (displayState) {
        BuddyDisplayState.SLEEP     -> "sleeping"          to C.textSecondary
        BuddyDisplayState.SEARCHING -> "searching…"        to C.teal
        BuddyDisplayState.IDLE      -> "idle"              to C.textSecondary
        BuddyDisplayState.BUSY      -> "working"           to C.teal
        BuddyDisplayState.ATTENTION -> "needs you"         to C.amber
        BuddyDisplayState.CELEBRATE -> "level up!"         to C.green
        BuddyDisplayState.APPROVAL  -> "awaiting approval" to C.amber
    }
    Text(label, style = MaterialTheme.typography.bodyLarge, color = color, modifier = modifier)
}

// ── Session chips ─────────────────────────────────────────────────────────────

@Composable
fun SessionChips(snapshot: Snapshot, C: BuddyColors = LocalBuddyColors.current) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("sessions", "${snapshot.total}",   C.textSecondary, false, C)
        Chip("running",  "${snapshot.running}", if (snapshot.running > 0) C.teal else C.textSecondary, snapshot.running > 0, C)
        Chip("waiting",  "${snapshot.waiting}", if (snapshot.waiting > 0) C.amber else C.textSecondary, snapshot.waiting > 0, C)
    }
}

@Composable
private fun Chip(label: String, value: String, color: Color, pulse: Boolean, C: BuddyColors) {
    val inf = rememberInfiniteTransition(label = "chip_$label")
    val alpha by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
    Surface(shape = RoundedCornerShape(20.dp), color = C.card, modifier = Modifier.drawAlpha(if (pulse && !C.isLight) alpha else 1f)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = C.textSecondary)
        }
    }
}

// ── Token meter ───────────────────────────────────────────────────────────────

@Composable
fun TokenMeter(tokens: Long, tokensToday: Long, level: Int, C: BuddyColors = LocalBuddyColors.current) {
    val prog = (tokensToday % 50_000) / 50_000f
    val animProg by animateFloatAsState(prog, tween(800), label = "arc")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(44.dp)) {
                val s = 4.dp.toPx()
                drawArc(C.card, -90f, 360f, false, style = Stroke(s, cap = StrokeCap.Round))
                drawArc(C.coral, -90f, animProg * 360f, false, style = Stroke(s, cap = StrokeCap.Round))
            }
            Text("L$level", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = C.coral)
        }
        Column {
            Text(formatTokens(tokensToday), fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = C.textPrimary)
            Text("today  ·  ${formatTokens(tokens)} total", style = MaterialTheme.typography.labelSmall, color = C.textSecondary)
        }
    }
}

// ── Chat panel ────────────────────────────────────────────────────────────────

@Composable
fun ChatPanel(chat: List<ChatEntry>, C: BuddyColors = LocalBuddyColors.current, modifier: Modifier = Modifier) {
    val reversed = remember(chat) { chat.reversed() }
    val listState = rememberLazyListState()
    LaunchedEffect(reversed.size) { if (reversed.isNotEmpty()) listState.animateScrollToItem(reversed.size - 1) }
    Column(modifier = modifier) {
        if (reversed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Conversation will appear here\nonce Claude Code is active",
                    style = MaterialTheme.typography.bodyLarge,
                    color = C.textSecondary.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(reversed, key = { index, _ -> index }) { _, entry ->
                    ChatBubble(entry, C)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry, C: BuddyColors) {
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
            color = if (isUser) C.coral.copy(alpha = if (C.isLight) 0.15f else 0.25f) else C.card,
            border = if (C.isLight) androidx.compose.foundation.BorderStroke(1.dp, C.divider) else null,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isUser) "you" else "claude",
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = if (isUser) C.coral else C.textSecondary,
                    )
                    if (entry.session.isNotEmpty()) {
                        Text("[${entry.session}]", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = C.coralDim)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(entry.text, style = MaterialTheme.typography.bodySmall, color = C.textPrimary)
            }
        }
    }
}

// ── Tools panel ───────────────────────────────────────────────────────────────

@Composable
fun ToolsPanel(entries: List<String>, C: BuddyColors = LocalBuddyColors.current, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Tool calls will appear here", style = MaterialTheme.typography.bodyLarge,
                    color = C.textSecondary.copy(alpha = 0.4f), textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(entries, key = { index, _ -> index }) { _, entry ->
                    Text(entry, style = MaterialTheme.typography.bodySmall, color = C.textPrimary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
        }
    }
}

// ── Approval card ─────────────────────────────────────────────────────────────

@Composable
private fun ApprovalCard(state: BuddyUiState, C: BuddyColors, onApprove: () -> Unit, onDeny: () -> Unit) {
    val prompt = state.snapshot.prompt ?: return
    Box(
        modifier = Modifier.fillMaxSize().background(C.background.copy(alpha = if (C.isLight) 0.95f else 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp), color = C.card,
            border = if (C.isLight) androidx.compose.foundation.BorderStroke(1.dp, C.divider) else null,
            modifier = Modifier.width(380.dp), tonalElevation = if (C.isLight) 4.dp else 8.dp,
        ) {
            Column(modifier = Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Permission Request", style = MaterialTheme.typography.headlineLarge, color = C.amber)
                Surface(shape = RoundedCornerShape(8.dp), color = C.surface) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(prompt.tool, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = C.coral)
                        if (prompt.hint.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(prompt.hint, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = C.textPrimary)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDeny, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = C.red)) {
                        Text("Deny", color = Color.White)
                    }
                    Button(onClick = onApprove, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = C.green)) {
                        Text("Approve", color = Color.White)
                    }
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatTokens(n: Long) = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
    n >= 1_000     -> "${"%.1f".format(n / 1_000.0)}K"
    else           -> "$n"
}
