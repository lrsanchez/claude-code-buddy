package com.claude.buddy.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatEntry(
    val role: String,       // "user" | "assistant"
    val text: String,
    val session: String = "", // session tag when multiple sessions active
)

@Serializable
data class SessionInfo(
    val id: String = "",
    val short: String = "",
    val running: Boolean = false,
    val waiting: Boolean = false,
    val tokens: Long = 0L,
    val entries: List<String> = emptyList(),
    val chat: List<ChatEntry> = emptyList(),
)

// Inbound: heartbeat snapshot
@Serializable
data class Snapshot(
    val total: Int = 0,
    val running: Int = 0,
    val waiting: Int = 0,
    val msg: String = "",
    val entries: List<String> = emptyList(),
    val tokens: Long = 0L,
    @SerialName("tokens_today") val tokensToday: Long = 0L,
    val prompt: PromptRequest? = null,
    val chat: List<ChatEntry> = emptyList(),
    val sessions: List<SessionInfo> = emptyList(),
    val s: Int = -1,    // session usage % (-1 = not yet known)
    val sr: Int = 0,    // session reset minutes
    val w: Int = -1,    // weekly usage %
    val wr: Int = 0,    // weekly reset minutes
)

@Serializable
data class PromptRequest(
    val id: String,
    val tool: String = "",
    val hint: String = "",
)

// Inbound: turn event
@Serializable
data class TurnEvent(
    val evt: String,
    val role: String = "",
    val content: List<JsonElement> = emptyList(),
)

// Inbound: time sync
@Serializable
data class TimeSync(
    val time: List<Long>,
)

// Inbound: commands from desktop
@Serializable
data class DesktopCommand(
    val cmd: String,
    val name: String? = null,
)

// Outbound: permission decision
@Serializable
data class PermissionDecision(
    val cmd: String = "permission",
    val id: String,
    val decision: String, // "once" or "deny"
)

// Outbound: generic ack
@Serializable
data class Ack(
    val ack: String,
    val ok: Boolean = true,
    val error: String? = null,
)

// Outbound: status ack data
@Serializable
data class StatusAck(
    val ack: String = "status",
    val ok: Boolean = true,
    val data: StatusData,
)

@Serializable
data class StatusData(
    val name: String,
    val sec: Boolean = false,
    val bat: BatStatus? = null,
    val sys: SysStatus? = null,
    val stats: StatsData? = null,
)

@Serializable
data class BatStatus(
    val pct: Int,
    @SerialName("mV") val mv: Int? = null,
    @SerialName("mA") val ma: Int? = null,
    val usb: Boolean = false,
)

@Serializable
data class SysStatus(
    val up: Long,
    val heap: Long? = null,
)

@Serializable
data class StatsData(
    val appr: Int = 0,
    val deny: Int = 0,
    val lvl: Int = 0,
)
