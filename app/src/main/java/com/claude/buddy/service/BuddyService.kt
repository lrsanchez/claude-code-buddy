package com.claude.buddy.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.claude.buddy.MainActivity
import com.claude.buddy.R
import com.claude.buddy.net.BuddyHttpClient
import com.claude.buddy.state.BuddyStateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private const val TAG        = "BuddyService"
private const val CHANNEL_ID = "buddy_service"
private const val NOTIF_ID   = 1
private const val POLL_MS    = 3_000L
private const val DEAD_MS    = 15_000L   // mark disconnected after this many ms without a good response

class BuddyService : Service() {

    inner class LocalBinder : Binder() {
        val service: BuddyService get() = this@BuddyService
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var stateManager: BuddyStateManager
        private set

    private var pollJob: Job? = null
    private var lastSuccessMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        stateManager = BuddyStateManager(applicationContext, scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        stateManager.onSearching()
        while (true) {
            val st = stateManager.state.value
            if (!st.isConfigured) {
                delay(POLL_MS)
                continue
            }
            val client = BuddyHttpClient(st.daemonUrl, st.daemonToken)
            val snapshot = client.fetchStatus()
            if (snapshot != null) {
                lastSuccessMs = System.currentTimeMillis()
                stateManager.onSnapshot(snapshot)
            } else {
                val sinceSuccess = System.currentTimeMillis() - lastSuccessMs
                if (lastSuccessMs > 0 && sinceSuccess > DEAD_MS) {
                    Log.w(TAG, "No response for ${sinceSuccess}ms — marking disconnected")
                    stateManager.onDisconnected()
                    lastSuccessMs = 0
                }
            }
            delay(POLL_MS)
        }
    }

    fun sendDecision(id: String, approve: Boolean) {
        val st = stateManager.state.value
        if (!st.isConfigured) return
        scope.launch {
            val client = BuddyHttpClient(st.daemonUrl, st.daemonToken)
            client.sendDecision(id, approve)
            if (approve) stateManager.onApprove() else stateManager.onDeny()
            stateManager.onDecisionMade(id)
        }
    }

    fun clearChat() {
        val st = stateManager.state.value
        if (!st.isConfigured) return
        scope.launch {
            BuddyHttpClient(st.daemonUrl, st.daemonToken).clearChat()
        }
    }

    fun reconnect() {
        lastSuccessMs = 0
        startPolling()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
