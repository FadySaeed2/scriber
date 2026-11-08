package com.fadysaeed.scriber.lab

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

object DeviceInfo {
    fun snapshot(context: Context): DeviceSnapshot {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(context.noBackupFilesDir.path)
        return DeviceSnapshot(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ").ifBlank { Build.HARDWARE },
            android = Build.VERSION.RELEASE,
            cores = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = mem.totalMem,
            availableRamBytes = mem.availMem,
            freeStorageBytes = stat.availableBytes,
            thermal = thermal(context),
            thermalHeadroom = headroom(context),
        )
    }

    fun thermal(context: Context): String = when (context.getSystemService(PowerManager::class.java).currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "cool"
        PowerManager.THERMAL_STATUS_LIGHT -> "warm"
        PowerManager.THERMAL_STATUS_MODERATE -> "hot (moderate)"
        PowerManager.THERMAL_STATUS_SEVERE -> "hot (severe)"
        PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "too hot"
        else -> "unknown"
    }

    /** 0 means cool, 1 means the phone is about to slow itself down. Null when the phone does not say. */
    fun headroom(context: Context): Float? =
        context.getSystemService(PowerManager::class.java).getThermalHeadroom(10).takeIf { !it.isNaN() }

    fun freeStorage(context: Context): Long = StatFs(context.noBackupFilesDir.path).availableBytes
}
