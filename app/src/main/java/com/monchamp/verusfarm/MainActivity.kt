package com.monchamp.verusfarm

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monchamp.verusfarm.ui.MainScreen
import com.monchamp.verusfarm.ui.VerusFarmTheme

class MainActivity : ComponentActivity() {

    private var checks by mutableStateOf(Checks())
    private var wallet by mutableStateOf("")
    private var customName by mutableStateOf("")
    private var mode by mutableStateOf(PowerMode.MAX)

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshChecks() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        wallet = Prefs.wallet(this)
        customName = Prefs.customName(this)
        mode = Prefs.mode(this)

        // Toute première ouverture : le minage démarre tout seul
        if (Prefs.isFirstRun(this)) {
            Prefs.markFirstRunDone(this)
            Prefs.setEnabled(this, true)
            askNotificationPermission()
        }

        MiningState.set { it.copy(enabled = Prefs.isEnabled(this)) }
        refreshChecks()
        NetworkWake.register(this)
        if (Prefs.isEnabled(this)) startMining()

        setContent {
            VerusFarmTheme {
                val ui by MiningState.ui.collectAsStateWithLifecycle()
                MainScreen(
                    ui = ui,
                    wallet = wallet,
                    customName = customName,
                    autoName = DeviceIdentity.autoName(this),
                    mode = mode,
                    checks = checks,
                    abi = DeviceIdentity.abi(),
                    cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                    onToggle = { if (ui.enabled) stopMining() else startMining() },
                    onModeChange = { m ->
                        Prefs.setMode(this, m)
                        mode = m
                        if (Prefs.isEnabled(this)) startMining()
                    },
                    onSaveIdentity = { w, n -> saveIdentity(w, n) },
                    onAskNotif = { askNotificationPermission() },
                    onAskBattery = { askBatteryExemption() },
                    onAskAdmin = { askAdmin() },
                    onOpenAppSettings = { openAppSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshChecks()
        wallet = Prefs.wallet(this)
    }

    // ---------- Minage ----------
    private fun startMining(reload: Boolean = false) {
        if (!Config.isUsableWallet(Prefs.wallet(this))) {
            Prefs.setEnabled(this, false)
            MiningState.set {
                it.copy(enabled = false, status = "Entre ton adresse VRSC pour commencer", tone = Tone.WARN)
            }
            return
        }
        Prefs.setEnabled(this, true)
        MiningState.set { it.copy(enabled = true) }
        try {
            val intent = Intent(this, MiningService::class.java)
                .putExtra(MiningService.EXTRA_RELOAD, reload)
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            MiningState.set {
                it.copy(status = "Impossible de démarrer le minage : ${e.message}", tone = Tone.ERROR)
            }
        }
    }

    private fun stopMining() {
        Prefs.setEnabled(this, false)
        MiningState.set {
            it.copy(enabled = false, status = "Arrêté", tone = Tone.IDLE, hashrate = "—", threads = 0)
        }
        try {
            startService(Intent(this, MiningService::class.java).setAction(MiningService.ACTION_STOP))
        } catch (_: Exception) {
        }
    }

    private fun saveIdentity(newWallet: String, newName: String) {
        val wasMissing = !Config.isUsableWallet(Prefs.wallet(this))
        Prefs.setWallet(this, newWallet)
        Prefs.setCustomName(this, newName)
        wallet = Prefs.wallet(this)
        customName = Prefs.customName(this)
        if (Prefs.isEnabled(this) || wasMissing) startMining(reload = true)
    }

    // ---------- Protections ----------
    private fun refreshChecks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        checks = Checks(
            notif = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            battery = pm.isIgnoringBatteryOptimizations(packageName),
            admin = dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
        )
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun askBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun askAdmin() {
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Empêche la désinstallation accidentelle de VerusFarm."
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }
}
