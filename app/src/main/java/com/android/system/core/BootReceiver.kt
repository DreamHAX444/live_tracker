package com.android.system.core

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Boot completed. Checking permissions before starting TrackerService...")
            
            // Do not attempt to start TrackerService if critical permissions are missing!
            // Doing so causes AppOps SecurityExceptions and can get the app killed by Android.
            if (hasRequiredPermissions(context)) {
                Log.d("BootReceiver", "All permissions granted. Starting TrackerService in foreground.")
                val serviceIntent = Intent(context, TrackerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.w("BootReceiver", "Missing permissions! Cannot start TrackerService on boot. Waiting for user to open the app.")
            }
        }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        
        var bgLocation = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bgLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        return fineLocation && bgLocation
    }
}
