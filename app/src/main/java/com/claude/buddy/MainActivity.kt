package com.claude.buddy

import android.content.*
import android.os.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claude.buddy.ble.NusAdvertiser
import com.claude.buddy.permissions.REQUIRED_PERMISSIONS
import com.claude.buddy.permissions.allPermissionsGranted
import com.claude.buddy.service.BuddyService
import com.claude.buddy.ui.BuddyScreen
import com.claude.buddy.ui.UnsupportedScreen
import com.claude.buddy.ui.theme.BuddyTheme
import com.claude.buddy.state.BuddyUiState

class MainActivity : ComponentActivity() {

    // mutableStateOf so Compose reacts when the service binds/unbinds
    private var buddyService by mutableStateOf<BuddyService?>(null)
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            buddyService = (binder as BuddyService.LocalBinder).service
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            buddyService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) checkCapabilityAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!allPermissionsGranted()) {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        } else {
            checkCapabilityAndStart()
        }

        setContent {
            BuddyTheme {
                val service = buddyService
                if (service != null) {
                    val state by service.stateManager.state.collectAsStateWithLifecycle()
                    BuddyScreen(
                        state = state,
                        onApprove = {
                            val id = state.snapshot.prompt?.id ?: return@BuddyScreen
                            service.sendDecision(id, approve = true)
                        },
                        onDeny = {
                            val id = state.snapshot.prompt?.id ?: return@BuddyScreen
                            service.sendDecision(id, approve = false)
                        },
                    )
                } else {
                    BuddyScreen(state = BuddyUiState(), onApprove = {}, onDeny = {})
                }
            }
        }
    }

    private fun checkCapabilityAndStart() {
        val cap = NusAdvertiser(this).checkCapability()
        if (!cap.supported) {
            setContent { BuddyTheme { UnsupportedScreen(reason = cap.reason) } }
            return
        }
        requestBatteryOptimizationExemption()
        startAndBindService()
    }

    private fun startAndBindService() {
        val intent = Intent(this, BuddyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            }
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
