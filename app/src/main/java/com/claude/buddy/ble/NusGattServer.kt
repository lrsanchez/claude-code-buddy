package com.claude.buddy.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "NusGattServer"

// Nordic UART Service — desktop writes to RX, device notifies on TX
val NUS_SERVICE_UUID:  UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
val NUS_RX_UUID:       UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // central writes here
val NUS_TX_UUID:       UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // device notifies here
val CCCD_UUID:         UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// Decision characteristic — central polls this to pick up Approve/Deny decisions.
// READ-only, polled every 500ms by the daemon. More reliable than TX notifications
// because GATT READ is a request/response (always acknowledged), with no CCCD dependency.
// Works identically on ESP32 / nRF52 / any BLE central.
val DECISION_CHAR_UUID: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")

interface GattServerListener {
    fun onLineReceived(device: BluetoothDevice, line: String)
    fun onDeviceConnected(device: BluetoothDevice)
    fun onDeviceDisconnected(device: BluetoothDevice)
}

@SuppressLint("MissingPermission")
class NusGattServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: GattServerListener,
) {
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val framers           = ConcurrentHashMap<String, LineFramer>()
    private val mtuMap            = ConcurrentHashMap<String, Int>()
    private val subscribedDevices = ConcurrentHashMap<String, BluetoothDevice>()

    // Pending decision stored here; daemon reads via GATT READ on DECISION_CHAR_UUID.
    // Cleared after first successful read so it's only consumed once.
    private val pendingDecision = AtomicReference<ByteArray?>(null)

    private val notifyChannel = Channel<Pair<BluetoothDevice, ByteArray>>(Channel.UNLIMITED)

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected: ${device.address}")
                    framers[device.address]           = LineFramer()
                    mtuMap[device.address]            = 23
                    subscribedDevices[device.address] = device
                    listener.onDeviceConnected(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected: ${device.address}")
                    framers.remove(device.address)
                    mtuMap.remove(device.address)
                    subscribedDevices.remove(device.address)
                    pendingDecision.set(null) // clear stale decision on disconnect
                    listener.onDeviceDisconnected(device)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtuMap[device.address] = mtu
            Log.d(TAG, "MTU $mtu for ${device.address}")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray,
        ) {
            if (characteristic.uuid != NUS_RX_UUID) return
            gattServer?.sendResponse(device, requestId, GATT_SUCCESS, 0, null)
            val framer = framers.getOrPut(device.address) { LineFramer() }
            framer.feed(value).forEach { listener.onLineReceived(device, it) }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != DECISION_CHAR_UUID) return
            // Serve pending decision; clear atomically so it's consumed exactly once
            val value = pendingDecision.getAndSet(null) ?: ByteArray(0)
            val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            gattServer?.sendResponse(device, requestId, GATT_SUCCESS, offset, slice)
            Log.d(TAG, "DECISION read by ${device.address}: ${value.toString(Charsets.UTF_8).take(80)}")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                subscribedDevices[device.address] = device
                Log.d(TAG, "TX subscribed: ${device.address}")
                // Notify connected so the dead-link timer resets — some devices
                // delay or skip writes briefly after connecting
                listener.onDeviceConnected(device)
            } else {
                subscribedDevices.remove(device.address)
                Log.d(TAG, "TX unsubscribed: ${device.address}")
            }
            if (responseNeeded) gattServer?.sendResponse(device, requestId, GATT_SUCCESS, 0, null)
        }
    }

    fun start() {
        val server = context.getSystemService(BluetoothManager::class.java)
            .openGattServer(context, callback) ?: run {
            Log.e(TAG, "Failed to open GATT server"); return
        }
        gattServer = server

        val rxChar = BluetoothGattCharacteristic(
            NUS_RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val txChar = BluetoothGattCharacteristic(
            NUS_TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).also {
            it.addDescriptor(BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ))
        }
        // Decision characteristic: central polls this to pick up user's Approve/Deny
        val decisionChar = BluetoothGattCharacteristic(
            DECISION_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        txCharacteristic = txChar

        val service = BluetoothGattService(NUS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)
        service.addCharacteristic(decisionChar)
        server.addService(service)

        scope.launch { drainNotifyQueue() }
        Log.i(TAG, "GATT server started (RX + TX + DECISION)")
    }

    fun stop() {
        gattServer?.close()
        gattServer = null
        subscribedDevices.clear()
        framers.clear()
    }

    /** Store a decision for the daemon to pick up via GATT READ poll. */
    fun setDecision(json: String) {
        pendingDecision.set(json.toByteArray(Charsets.UTF_8))
        Log.d(TAG, "Decision stored for poll: $json")
    }

    fun send(line: String) {
        val bytes     = (line + "\n").toByteArray(Charsets.UTF_8)
        val mtu       = subscribedDevices.values.firstOrNull()?.let { mtuMap[it.address] } ?: 23
        val chunkSize = maxOf(mtu - 3, 1)
        bytes.toList().chunked(chunkSize).forEach { chunk ->
            val device = subscribedDevices.values.firstOrNull() ?: return
            scope.launch { notifyChannel.send(Pair(device, chunk.toByteArray())) }
        }
    }

    private suspend fun drainNotifyQueue() {
        for ((device, chunk) in notifyChannel) {
            val tx = txCharacteristic ?: continue
            @Suppress("DEPRECATION") tx.value = chunk
            @Suppress("DEPRECATION") gattServer?.notifyCharacteristicChanged(device, tx, false)
            delay(15)
        }
    }
}
