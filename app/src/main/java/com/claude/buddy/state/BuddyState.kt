package com.claude.buddy.state

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.claude.buddy.protocol.Snapshot
import com.claude.buddy.protocol.StatusAck
import com.claude.buddy.protocol.StatusData
import com.claude.buddy.protocol.BatStatus
import com.claude.buddy.protocol.SysStatus
import com.claude.buddy.protocol.StatsData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class BuddyDisplayState {
    SLEEP, SEARCHING, IDLE, BUSY, ATTENTION, CELEBRATE, APPROVAL
}

data class BuddyUiState(
    val displayState: BuddyDisplayState = BuddyDisplayState.SLEEP,
    val snapshot: Snapshot = Snapshot(),
    val ownerName: String = "",
    val deviceName: String = "Clawd",
    val isConnected: Boolean = false,
    val timeSyncEpoch: Long = 0L,
    val timeSyncTzOffset: Int = 0,
    // persisted
    val level: Int = 0,
    val approveCount: Int = 0,
    val denyCount: Int = 0,
    // transient UI
    val pendingCelebrate: Boolean = false,
    // persisted preference
    val isLightMode: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("buddy_stats")

private val KEY_LEVEL      = intPreferencesKey("level")
private val KEY_APPR       = intPreferencesKey("approve_count")
private val KEY_DENY       = intPreferencesKey("deny_count")
private val KEY_OWNER      = stringPreferencesKey("owner_name")
private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
private val KEY_LIGHT_MODE = booleanPreferencesKey("light_mode")

class BuddyStateManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(BuddyUiState())
    val state: StateFlow<BuddyUiState> = _state.asStateFlow()

    private val serviceStartMs = System.currentTimeMillis()

    init {
        scope.launch {
            context.dataStore.data.first().let { prefs ->
                _state.update {
                    it.copy(
                        level       = prefs[KEY_LEVEL] ?: 0,
                        approveCount = prefs[KEY_APPR] ?: 0,
                        denyCount   = prefs[KEY_DENY] ?: 0,
                        ownerName   = prefs[KEY_OWNER] ?: "",
                        deviceName  = prefs[KEY_DEVICE_NAME] ?: "Clawd",
                        isLightMode = prefs[KEY_LIGHT_MODE] ?: false,
                    )
                }
            }
        }
    }

    fun onConnected() = _state.update { it.copy(isConnected = true, displayState = BuddyDisplayState.IDLE) }

    fun onSearching() = _state.update { it.copy(isConnected = false, displayState = BuddyDisplayState.SEARCHING) }

    fun onDisconnected() = _state.update {
        it.copy(isConnected = false, displayState = BuddyDisplayState.SLEEP, snapshot = Snapshot())
    }

    // Track the last prompt we already decided on so re-sent heartbeats don't re-show the card
    private var lastDecidedPromptId: String? = null

    fun onSnapshot(snapshot: Snapshot) {
        val current = _state.value
        val newLevel = (snapshot.tokens / 50_000).toInt()
        val celebrate = newLevel > current.level
        if (celebrate) scope.launch { persistLevel(newLevel) }

        // Suppress re-delivery of a prompt we already decided on
        val effectivePrompt = snapshot.prompt?.takeIf { it.id != lastDecidedPromptId }
        val effectiveSnapshot = if (effectivePrompt == null && snapshot.prompt != null)
            snapshot.copy(prompt = null) else snapshot

        val displayState = when {
            effectivePrompt != null    -> BuddyDisplayState.APPROVAL
            snapshot.waiting > 0      -> BuddyDisplayState.ATTENTION
            snapshot.running > 0      -> BuddyDisplayState.BUSY
            else                      -> BuddyDisplayState.IDLE
        }

        _state.update {
            it.copy(
                snapshot = effectiveSnapshot,
                displayState = displayState,
                level = if (celebrate) newLevel else it.level,
                pendingCelebrate = celebrate,
            )
        }
    }

    fun onTimeSync(epoch: Long, tzOffset: Int) = _state.update {
        it.copy(timeSyncEpoch = epoch, timeSyncTzOffset = tzOffset)
    }

    fun onOwner(name: String) {
        scope.launch { context.dataStore.edit { it[KEY_OWNER] = name } }
        _state.update { it.copy(ownerName = name) }
    }

    fun toggleTheme() {
        val next = !_state.value.isLightMode
        scope.launch { context.dataStore.edit { it[KEY_LIGHT_MODE] = next } }
        _state.update { it.copy(isLightMode = next) }
    }

    // Call immediately on button tap: dismiss card locally + suppress re-delivery of same prompt
    fun onDecisionMade(promptId: String) {
        lastDecidedPromptId = promptId
        _state.update {
            it.copy(
                snapshot = it.snapshot.copy(prompt = null),
                displayState = if (it.snapshot.waiting > 0) BuddyDisplayState.ATTENTION
                               else if (it.snapshot.running > 0) BuddyDisplayState.BUSY
                               else BuddyDisplayState.IDLE,
            )
        }
    }

    fun onApprove() {
        val count = _state.value.approveCount + 1
        scope.launch { context.dataStore.edit { it[KEY_APPR] = count } }
        _state.update { it.copy(approveCount = count) }
    }

    fun onDeny() {
        val count = _state.value.denyCount + 1
        scope.launch { context.dataStore.edit { it[KEY_DENY] = count } }
        _state.update { it.copy(denyCount = count) }
    }

    fun onDeadLink() = _state.update {
        it.copy(isConnected = false, displayState = BuddyDisplayState.SLEEP, snapshot = Snapshot())
    }

    fun clearCelebrate() = _state.update { it.copy(pendingCelebrate = false) }

    private suspend fun persistLevel(level: Int) {
        context.dataStore.edit { it[KEY_LEVEL] = level }
    }

    fun buildStatusAck(): StatusAck {
        val st = _state.value
        val bm = context.getSystemService(BatteryManager::class.java)
        val bat = bm?.let {
            BatStatus(
                pct = it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                ma = it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { a -> a != Int.MIN_VALUE },
                usb = it.isCharging,
            )
        }
        val uptimeSec = (System.currentTimeMillis() - serviceStartMs) / 1000
        return StatusAck(
            data = StatusData(
                name = st.deviceName,
                sec = false,
                bat = bat,
                sys = SysStatus(up = uptimeSec, heap = Runtime.getRuntime().freeMemory()),
                stats = StatsData(appr = st.approveCount, deny = st.denyCount, lvl = st.level),
            )
        )
    }
}
