package com.claude.buddy.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log

private const val TAG = "NusAdvertiser"

data class PeripheralCapability(
    val supported: Boolean,
    val reason: String = "",
)

@SuppressLint("MissingPermission")
class NusAdvertiser(private val context: Context) {

    private var advertiser: BluetoothLeAdvertiser? = null

    fun checkCapability(): PeripheralCapability {
        val btManager = context.getSystemService(BluetoothManager::class.java)
        val adapter = btManager?.adapter
            ?: return PeripheralCapability(false, "No Bluetooth adapter")
        if (!adapter.isEnabled)
            return PeripheralCapability(false, "Bluetooth is off")
        if (!adapter.isMultipleAdvertisementSupported)
            return PeripheralCapability(false, "Multiple advertisement not supported")
        if (adapter.bluetoothLeAdvertiser == null)
            return PeripheralCapability(false, "BLE advertiser unavailable — device cannot act as a peripheral")
        return PeripheralCapability(true)
    }

    fun start(deviceName: String) {
        val btManager = context.getSystemService(BluetoothManager::class.java)
        val adapter = btManager?.adapter ?: return

        // Set a short adapter name so the scan response fits in 31 bytes
        adapter.name = deviceName

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser is null — cannot advertise")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0) // advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        // Advertisement packet: NUS service UUID
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .build()

        // Scan response: device name (so desktop can filter "Claude…")
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        Log.i(TAG, "Started advertising as '$deviceName'")
    }

    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started: $settingsInEffect")
        }
        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                else -> "unknown ($errorCode)"
            }
            Log.e(TAG, "Advertising failed: $reason")
        }
    }

    companion object {
        fun buildDeviceName(adapter: BluetoothAdapter): String {
            // "Claude" + last 4 hex chars of MAC (XX:XX:XX:XX:YY:ZZ → YYZZ)
            val mac = adapter.address?.replace(":", "") ?: "0000"
            val suffix = mac.takeLast(4).uppercase()
            return "Claude$suffix"
        }
    }
}
