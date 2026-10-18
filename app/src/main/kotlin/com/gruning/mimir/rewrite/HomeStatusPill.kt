package com.gruning.mimir.rewrite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.gruning.mimir.rewrite.core.ui.MimirLiquidStatusPill
import com.gruning.mimir.rewrite.core.ui.mimirStatusPillClockLabel
import java.util.Calendar

/**
 * FUX-02 R3 — data host for the Liquid Mimir status pill. Everything is receiver-driven (sticky
 * battery broadcast, ACTION_TIME_TICK minute clock, default-network callback for wifi STATE): zero
 * polling, all callbacks unregistered with the surface. Wifi is display-only — no network feature.
 */
@Composable
internal fun HomeStatusPillHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    // Seeded from the sticky battery broadcast and the active network, read without registering, so
    // the pill is right from its first frame. Every destination switch composes it afresh, and battery used to be
    // blank and wifi off until the callbacks landed -- wifi could pop in after the switch had ended.
    val stickyBattery = remember { homeStatusStickyBattery(context) }
    var batteryLevel by remember { mutableStateOf(homeStatusBatteryPercent(stickyBattery)) }
    var charging by remember { mutableStateOf(homeStatusCharging(stickyBattery)) }
    var wifiConnected by remember { mutableStateOf(homeStatusWifiNow(context)) }
    var clock by remember { mutableStateOf(currentClockLabel()) }

    DisposableEffect(context) {
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                batteryLevel = if (level >= 0 && scale > 0) (level * 100) / scale else null
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        // Sticky: registration returns the current battery intent immediately.
        batteryReceiver.onReceive(
            context,
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)),
        )
        val tickReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                clock = currentClockLabel()
            }
        }
        context.registerReceiver(
            tickReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
        )
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                wifiConnected = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }

            override fun onLost(network: Network) {
                wifiConnected = false
            }
        }
        connectivity?.registerDefaultNetworkCallback(networkCallback)
        onDispose {
            context.unregisterReceiver(batteryReceiver)
            context.unregisterReceiver(tickReceiver)
            connectivity?.unregisterNetworkCallback(networkCallback)
        }
    }

    MimirLiquidStatusPill(
        clockLabel = clock,
        batteryPercent = batteryLevel,
        charging = charging,
        wifiConnected = wifiConnected,
        modifier = modifier,
    )
}

private fun currentClockLabel(): String {
    val now = Calendar.getInstance()
    return mimirStatusPillClockLabel(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
}

/** The sticky battery broadcast, read without registering a receiver. */
private fun homeStatusStickyBattery(context: Context): Intent? =
    runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()

internal fun homeStatusBatteryPercent(intent: Intent?): Int? {
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) (level * 100) / scale else null
}

internal fun homeStatusCharging(intent: Intent?): Boolean {
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
}

/** Whether the active network is wifi right now; false when unknown. */
private fun homeStatusWifiNow(context: Context): Boolean = runCatching {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
}.getOrDefault(false)
