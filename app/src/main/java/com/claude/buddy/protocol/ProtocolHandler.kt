package com.claude.buddy.protocol

import android.util.Log
import kotlinx.serialization.json.*

private const val TAG = "ProtocolHandler"

class ProtocolHandler(
    private val onSnapshot: (Snapshot) -> Unit,
    private val onTurn: (TurnEvent) -> Unit,
    private val onTimeSync: (Long, Int) -> Unit,
    private val onOwner: (String) -> Unit,
    private val buildStatusAck: () -> StatusAck,
    private val sendLine: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun handleLine(line: String) {
        Log.v(TAG, "RX: $line")
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: run {
            Log.w(TAG, "Non-JSON line: $line")
            return
        }

        when {
            // Turn event: { "evt": "turn", ... }
            obj["evt"]?.jsonPrimitive?.contentOrNull == "turn" -> {
                runCatching { json.decodeFromJsonElement<TurnEvent>(obj) }
                    .onSuccess { onTurn(it) }
                    .onFailure { Log.w(TAG, "Failed to parse turn event: ${it.message}") }
            }
            // Time sync: { "time": [epoch, tzOffset] }
            obj["time"] != null -> {
                val arr = obj["time"]?.jsonArray
                if (arr != null && arr.size >= 2) {
                    val epoch = arr[0].jsonPrimitive.long
                    val tz = arr[1].jsonPrimitive.int
                    onTimeSync(epoch, tz)
                }
            }
            // Desktop command: { "cmd": "...", ... }
            obj["cmd"] != null -> {
                val cmd = obj["cmd"]!!.jsonPrimitive.content
                when (cmd) {
                    "status" -> {
                        val ack = buildStatusAck()
                        sendLine(Json.encodeToString(StatusAck.serializer(), ack))
                    }
                    "owner" -> {
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                        onOwner(name)
                        sendLine(Json.encodeToString(Ack.serializer(), Ack(ack = "owner")))
                    }
                    "name" -> {
                        sendLine(Json.encodeToString(Ack.serializer(), Ack(ack = "name")))
                    }
                    "unpair" -> {
                        sendLine(Json.encodeToString(Ack.serializer(), Ack(ack = "unpair")))
                    }
                    else -> Log.w(TAG, "Unknown cmd: $cmd")
                }
            }
            // Heartbeat snapshot: has "total" field
            obj["total"] != null -> {
                runCatching { json.decodeFromJsonElement<Snapshot>(obj) }
                    .onSuccess { onSnapshot(it) }
                    .onFailure { Log.w(TAG, "Failed to parse snapshot: ${it.message}") }
            }
            else -> Log.w(TAG, "Unrecognized message: $line")
        }
    }

    fun buildPermissionDecision(id: String, approve: Boolean): String {
        val decision = PermissionDecision(id = id, decision = if (approve) "once" else "deny")
        return Json.encodeToString(PermissionDecision.serializer(), decision)
    }
}
