package com.example.autotapper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.Toast
import com.example.autotapper.R
import com.example.autotapper.data.ConfigStore
import com.example.autotapper.model.TapStep

/**
 * The heart of the app.
 *
 *  - Performs the configured taps by injecting gestures with [dispatchGesture].
 *  - Draws a small floating control panel and a full-screen capture overlay
 *    using TYPE_ACCESSIBILITY_OVERLAY windows. Because these belong to an
 *    accessibility service, they do NOT require the "draw over other apps"
 *    (SYSTEM_ALERT_WINDOW) permission.
 *
 * Educational note: an accessibility service is the only sanctioned way for an
 * Android app to tap on top of *other* apps. The user must switch it on by hand
 * in Settings > Accessibility; nothing here can bypass that.
 */
class AutoTapService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var controlPanel: View? = null
    private var captureOverlay: View? = null

    // --- macro run state ---
    private var running = false
    private var steps: List<TapStep> = emptyList()
    private var loopCount = 0          // 0 == loop forever
    private var currentIndex = 0
    private var currentLoop = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showControlPanel()
    }

    // We don't inspect screen content, so these are intentionally empty.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        stopMacro()
        removeControlPanel()
        removeCaptureOverlay()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Floating control panel
    // ---------------------------------------------------------------------

    private fun showControlPanel() {
        if (controlPanel != null) return

        val panel = LayoutInflater.from(this).inflate(R.layout.overlay_controls, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        panel.findViewById<Button>(R.id.btnStart).setOnClickListener { startMacro() }
        panel.findViewById<Button>(R.id.btnStop).setOnClickListener { stopMacro() }
        panel.findViewById<Button>(R.id.btnCapture).setOnClickListener { showCaptureOverlay() }
        panel.findViewById<Button>(R.id.btnHide).setOnClickListener {
            // Just push the panel to the screen edge; keep it reachable.
            lp.x = 0
            lp.y = 0
            windowManager.updateViewLayout(panel, lp)
        }

        enableDragging(panel, lp)

        windowManager.addView(panel, lp)
        controlPanel = panel
    }

    /** Lets the user drag the panel around by its handle. */
    private fun enableDragging(panel: View, lp: WindowManager.LayoutParams) {
        val handle = panel.findViewById<View>(R.id.dragHandle)
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(panel, lp)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeControlPanel() {
        controlPanel?.let { runCatching { windowManager.removeView(it) } }
        controlPanel = null
    }

    // ---------------------------------------------------------------------
    // Capture overlay: record a tap point by touching the screen
    // ---------------------------------------------------------------------

    private fun showCaptureOverlay() {
        if (captureOverlay != null) return

        val overlay = View(this).apply {
            setBackgroundColor(getColor(R.color.capture_scrim))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX
                val y = event.rawY
                // Default 1s wait after the tap; the user can fine-tune it in the app.
                ConfigStore.addStep(this, TapStep(x, y, postDelayMs = 1000L))
                Toast.makeText(
                    this,
                    "Captured (${x.toInt()}, ${y.toInt()})",
                    Toast.LENGTH_SHORT
                ).show()
                removeCaptureOverlay()
                true
            } else false
        }

        windowManager.addView(overlay, lp)
        captureOverlay = overlay
    }

    private fun removeCaptureOverlay() {
        captureOverlay?.let { runCatching { windowManager.removeView(it) } }
        captureOverlay = null
    }

    // ---------------------------------------------------------------------
    // Macro engine
    // ---------------------------------------------------------------------

    private fun startMacro() {
        if (running) return
        steps = ConfigStore.loadSteps(this)
        loopCount = ConfigStore.loadLoopCount(this)
        if (steps.isEmpty()) {
            Toast.makeText(this, "No steps configured", Toast.LENGTH_SHORT).show()
            return
        }
        currentIndex = 0
        currentLoop = 0
        running = true
        Toast.makeText(this, "AutoTapper started", Toast.LENGTH_SHORT).show()
        scheduleNext(0)
    }

    private fun stopMacro() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        Toast.makeText(this, "AutoTapper stopped", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleNext(delayMs: Long) {
        if (!running) return
        handler.postDelayed({ runStep() }, delayMs)
    }

    private fun runStep() {
        if (!running) return

        // Wrap around at the end of the sequence, honouring the loop limit.
        if (currentIndex >= steps.size) {
            currentIndex = 0
            currentLoop++
            if (loopCount != 0 && currentLoop >= loopCount) {
                stopMacro()
                return
            }
        }

        val step = steps[currentIndex]
        currentIndex++
        performTap(step) {
            // "tap → wait → next": schedule the following step after the delay.
            scheduleNext(step.postDelayMs)
        }
    }

    private fun performTap(step: TapStep, onDone: () -> Unit) {
        val path = Path().apply { moveTo(step.x, step.y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, step.tapDurationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = onDone()
                override fun onCancelled(g: GestureDescription?) = onDone()
            },
            handler
        )
        // If the system refuses the gesture, don't stall the loop.
        if (!dispatched) onDone()
    }
}
