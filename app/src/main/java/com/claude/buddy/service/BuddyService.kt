package com.claude.buddy.service

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.claude.buddy.MainActivity
import com.claude.buddy.R
import com.claude.buddy.ble.*
import com.claude.buddy.protocol.ProtocolHandler
import com.claude.buddy.state.BuddyStateManager
import kotlinx.coroutines.*

private const val TAG = "BuddyService"
private const val CHANNEL_ID = "buddy_service"
private const val NOTIF_ID = 1
private const val DEAD_LINK_MS = 30_000L

class BuddyService : Service() {

    inner class LocalBinder : Binder() {
        val service: BuddyService get() = this@BuddyService
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var stateManager: BuddyStateManager
        private set

    private lateinit var advertiser: NusAdvertiser
    private lateinit var gattServer: NusGattServer
    private lateinit var protocol: ProtocolHandler

    private var deadLinkJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        stateManager = BuddyStateManager(applicationContext, scope)

        advertiser = NusAdvertiser(this)
        gattServer = NusGattServer(this, scope, object : GattServerListener {
            override fun onLineReceived(device: BluetoothDevice, line: String) {
                resetDeadLinkTimer()
                protocol.handleLine(line)
            }
            override fun onDeviceConnected(device: BluetoothDevice) {
                Log.i(TAG, "Device connected: ${device.address}")
                stateManager.onConnected()
                resetDeadLinkTimer()
                // Stop advertising while a central is connected
                advertiser.stop()
            }
            override fun onDeviceDisconnected(device: BluetoothDevice) {
                Log.i(TAG, "Device disconnected: ${device.address}")
                stateManager.onDisconnected()
                deadLinkJob?.cancel()
                // Resume advertising so the desktop can reconnect
                startAdvertising()
            }
        })

        protocol = ProtocolHandler(
            onSnapshot = { stateManager.onSnapshot(it) },
            onTurn = { /* future: log or store last turn */ },
            onTimeSync = { epoch, tz -> stateManager.onTimeSync(epoch, tz) },
            onOwner = { stateManager.onOwner(it) },
            buildStatusAck = { stateManager.buildStatusAck() },
            sendLine = { gattServer.send(it) },
        )

        val cap = advertiser.checkCapability()
        if (!cap.supported) {
            Log.e(TAG, "BLE peripheral not supported: ${cap.reason}")
            stateManager.onDisconnected() // stays in SLEEP with error visible
            return
        }

        gattServer.start()
        startAdvertising()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val btManager = getSystemService(BluetoothManager::class.java)
        val adapter = btManager?.adapter ?: return
        val name = NusAdvertiser.buildDeviceName(adapter)
        advertiser.start(name)
    }

    private fun resetDeadLinkTimer() {
        deadLinkJob?.cancel()
        deadLinkJob = scope.launch {
            delay(DEAD_LINK_MS)
            Log.w(TAG, "Dead-link timeout — treating as disconnected")
            stateManager.onDeadLink()
            startAdvertising()
        }
    }

    fun sendDecision(id: String, approve: Boolean) {
        val json = """{"id":"$id","decision":"${if (approve) "once" else "deny"}"}"""
        gattServer.setDecision(json)
        gattServer.send(protocol.buildPermissionDecision(id, approve))
        if (approve) stateManager.onApprove() else stateManager.onDeny()
        stateManager.onDecisionMade(id)
    }

    fun reconnect() {
        advertiser.stop()
        startAdvertising()
        stateManager.onSearching() // show "searching" while daemon re-discovers
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onDestroy() {
        deadLinkJob?.cancel()
        advertiser.stop()
        gattServer.stop()
        scope.cancel()
        super.onDestroy()
    }

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
