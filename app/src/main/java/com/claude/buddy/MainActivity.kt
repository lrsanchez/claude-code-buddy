package com.claude.buddy

import android.content.*
import android.os.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.claude.buddy.permissions.REQUIRED_PERMISSIONS
import com.claude.buddy.permissions.allPermissionsGranted
import com.claude.buddy.service.BuddyService
import com.claude.buddy.state.BuddyUiState
import com.claude.buddy.ui.BuddyScreen
import com.claude.buddy.ui.SetupScreen
import com.claude.buddy.ui.theme.BuddyTheme

class MainActivity : ComponentActivity() {

    private var buddyService by mutableStateOf<BuddyService?>(null)
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            buddyService = (binder as BuddyService.LocalBinder).service
            serviceBound = true
            // Start HTTP polling once service is bound
            buddyService?.startPolling()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            buddyService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startAndBindService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!allPermissionsGranted()) {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        } else {
            startAndBindService()
        }

        setContent {
            val service  = buddyService
            val state    = service?.stateManager?.state?.collectAsStateWithLifecycle()?.value
                           ?: BuddyUiState()

            BuddyTheme(isLightMode = state.isLightMode) {
                if (!state.isConfigured) {
                    SetupScreen { url, token ->
                        service?.stateManager?.saveConfig(url, token)
                        service?.startPolling()
                    }
                } else if (service != null) {
                    // Auto-approve: fire on new prompt OR when toggle is switched on
                    // while a prompt is already pending.
                    val promptId = state.snapshot.prompt?.id
                    LaunchedEffect(promptId, state.autoApprove) {
                        if (promptId != null && state.autoApprove) {
                            service.sendDecision(promptId, approve = true)
                        }
                    }

                    BuddyScreen(
                        state     = state,
                        onApprove = {
                            val id = state.snapshot.prompt?.id ?: return@BuddyScreen
                            service.sendDecision(id, approve = true)
                        },
                        onDeny = {
                            val id = state.snapshot.prompt?.id ?: return@BuddyScreen
                            service.sendDecision(id, approve = false)
                        },
                        onToggleTheme        = { service.stateManager.toggleTheme() },
                        onReconnect          = { service.reconnect() },
                        onToggleAutoApprove  = { service.stateManager.toggleAutoApprove() },
                        onClearChat          = { service.clearChat() },
                    )
                } else {
                    BuddyScreen(state = state, onApprove = {}, onDeny = {})
                }
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, BuddyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
