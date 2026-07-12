package com.example.autotapper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Display
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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * The heart of the app.
 *
 *  - Performs taps by injecting gestures with [dispatchGesture].
 *  - Reads the screen with [takeScreenshot] to support image-conditional steps
 *    ("wait until this image appears, then tap there").
 *  - Draws a floating control panel and capture overlays using
 *    TYPE_ACCESSIBILITY_OVERLAY windows (no draw-over-apps permission needed).
 */
class AutoTapService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var controlPanel: View? = null
    private var captureOverlay: View? = null

    // --- on-screen draggable tap markers (edit mode) ---
    private val markerViews = mutableListOf<View>()
    private var editMode = false

    // --- macro run state ---
    private var running = false
    private var steps: List<TapStep> = emptyList()
    private var loopCount = 0          // 0 == loop forever
    private var currentIndex = 0
    private var currentLoop = 0

    companion object {
        private const val PATCH_SIZE = 120        // reference image is a square patch
        private const val RECHECK_MS = 1100L      // re-check interval (screenshot is rate-limited ~1s)
        private const val CAPTURE_SETTLE_MS = 200L // let the capture scrim disappear before screenshotting
        private const val MATCH_GRID = 16         // downscale size used when comparing images
        private const val MARKER_SIZE_DP = 48     // diameter of the draggable tap markers
        private const val DRAG_SLOP_DP = 8        // movement before a touch counts as a drag
        private const val LONG_PRESS_MS = 600L    // hold on a marker to delete it
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showControlPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        stopMacro()
        hideMarkers()
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
        panel.findViewById<Button>(R.id.btnAddPoint).setOnClickListener { addPointAtCenter() }
        panel.findViewById<Button>(R.id.btnEditPoints).setOnClickListener { toggleEditMode() }
        panel.findViewById<Button>(R.id.btnImgCond).setOnClickListener { captureImageConditionStep() }
        panel.findViewById<Button>(R.id.btnHide).setOnClickListener {
            lp.x = 0
            lp.y = 0
            windowManager.updateViewLayout(panel, lp)
        }

        enableDragging(panel, lp)

        windowManager.addView(panel, lp)
        controlPanel = panel
    }

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
    // Draggable on-screen tap markers (the "popular app" way to set points)
    // ---------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toggleEditMode() {
        editMode = !editMode
        if (editMode) {
            showMarkers()
            toast("โหมดแก้ไข: ลากวงกลมเพื่อย้าย • ลากค้างเพื่อลบ")
        } else {
            hideMarkers()
        }
    }

    /** Draw one draggable numbered circle per tap step at its coordinates. */
    private fun showMarkers() {
        hideMarkers()
        val size = dp(MARKER_SIZE_DP)
        val steps = ConfigStore.loadSteps(this)
        steps.forEachIndexed { index, step ->
            val marker = LayoutInflater.from(this)
                .inflate(R.layout.marker_view, null) as android.widget.TextView
            marker.text = (index + 1).toString()
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (step.x - size / 2).toInt()
                y = (step.y - size / 2).toInt()
            }
            attachMarkerDrag(marker, lp, index, size)
            runCatching { windowManager.addView(marker, lp) }
            markerViews.add(marker)
        }
        if (steps.isEmpty()) toast("ยังไม่มีจุด — กด ➕ จุด เพื่อเพิ่ม")
    }

    private fun hideMarkers() {
        markerViews.forEach { runCatching { windowManager.removeView(it) } }
        markerViews.clear()
    }

    private fun addPointAtCenter() {
        val dm = resources.displayMetrics
        ConfigStore.addStep(
            this,
            TapStep(x = dm.widthPixels / 2f, y = dm.heightPixels / 2f, postDelayMs = 1000L)
        )
        editMode = true
        showMarkers() // re-draw with the new marker included
        toast("เพิ่มจุดแล้ว ลากไปวางตำแหน่งที่ต้องการ")
    }

    private fun attachMarkerDrag(
        marker: View,
        lp: WindowManager.LayoutParams,
        index: Int,
        size: Int
    ) {
        val slop = dp(DRAG_SLOP_DP)
        var downX = 0f
        var downY = 0f
        var startLpX = 0
        var startLpY = 0
        var moved = false
        val longPress = Runnable { deleteStep(index) }

        marker.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startLpX = lp.x
                    startLpY = lp.y
                    moved = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        handler.removeCallbacks(longPress) // a drag is not a long-press
                    }
                    if (moved) {
                        lp.x = startLpX + dx.toInt()
                        lp.y = startLpY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(marker, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (moved) {
                        updateStepPosition(
                            index,
                            (lp.x + size / 2).toFloat(),
                            (lp.y + size / 2).toFloat()
                        )
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateStepPosition(index: Int, x: Float, y: Float) {
        val steps = ConfigStore.loadSteps(this)
        if (index in steps.indices) {
            steps[index] = steps[index].copy(x = x, y = y)
            ConfigStore.saveSteps(this, steps)
        }
    }

    private fun deleteStep(index: Int) {
        val steps = ConfigStore.loadSteps(this)
        if (index in steps.indices) {
            steps.removeAt(index)
            ConfigStore.saveSteps(this, steps)
            toast("ลบจุดที่ ${index + 1} แล้ว")
            showMarkers() // renumber the remaining markers
        }
    }

    // ---------------------------------------------------------------------
    // Full-screen capture overlay: records one touch point
    // ---------------------------------------------------------------------

    private fun showCapture(onTap: (Float, Float) -> Unit) {
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
                removeCaptureOverlay()
                onTap(x, y)
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

    /**
     * Two-tap flow to build an image-conditional step:
     *   1) tap where the trigger image lives  -> screenshot + save that patch
     *   2) tap where the action should happen -> store as the tap target
     */
    private fun captureImageConditionStep() {
        editMode = false
        hideMarkers() // keep markers out of the reference screenshot
        toast("1) แตะตรงตำแหน่ง 'ภาพ' ที่จะรอ")
        showCapture { imgX, imgY ->
            // Let the scrim clear, then grab the screen and crop the reference patch.
            handler.postDelayed({
                captureScreen(onBitmap = { bmp ->
                    val patch = crop(bmp, imgX, imgY, PATCH_SIZE)
                    bmp.recycle()
                    if (patch == null) {
                        toast("จับภาพไม่ได้ (ใกล้ขอบจอเกินไป)")
                        return@captureScreen
                    }
                    val fname = "cond_${System.currentTimeMillis()}.png"
                    savePng(patch, File(filesDir, fname))
                    patch.recycle()

                    toast("2) แตะ 'จุดที่จะกด'")
                    showCapture { tapX, tapY ->
                        ConfigStore.addStep(
                            this,
                            TapStep(
                                x = tapX, y = tapY, postDelayMs = 1000L,
                                condImage = fname, condCenterX = imgX, condCenterY = imgY
                            )
                        )
                        toast("เพิ่มแล้ว: รอภาพ → กด (${tapX.toInt()}, ${tapY.toInt()})")
                    }
                }, onError = { toast("ถ่ายภาพหน้าจอไม่ได้") })
            }, CAPTURE_SETTLE_MS)
        }
    }

    // ---------------------------------------------------------------------
    // Screen capture + image comparison
    // ---------------------------------------------------------------------

    private fun captureScreen(onBitmap: (Bitmap) -> Unit, onError: () -> Unit) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        buffer.close()
                        if (bmp != null) onBitmap(bmp) else onError()
                    }

                    override fun onFailure(errorCode: Int) = onError()
                }
            )
        } catch (e: Exception) {
            onError()
        }
    }

    private fun crop(src: Bitmap, cx: Float, cy: Float, size: Int): Bitmap? {
        if (src.width < size || src.height < size) return null
        val half = size / 2
        val left = (cx - half).toInt().coerceIn(0, src.width - size)
        val top = (cy - half).toInt().coerceIn(0, src.height - size)
        return Bitmap.createBitmap(src, left, top, size, size)
    }

    private fun savePng(bmp: Bitmap, file: File) {
        runCatching {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Similarity in 0..1 (1 == identical), compared on a small downscaled grid. */
    private fun similarity(a: Bitmap, b: Bitmap): Double {
        val n = MATCH_GRID
        val sa = Bitmap.createScaledBitmap(a, n, n, true)
        val sb = Bitmap.createScaledBitmap(b, n, n, true)
        var diff = 0L
        for (y in 0 until n) {
            for (x in 0 until n) {
                val pa = sa.getPixel(x, y)
                val pb = sb.getPixel(x, y)
                diff += abs(Color.red(pa) - Color.red(pb)).toLong()
                diff += abs(Color.green(pa) - Color.green(pb)).toLong()
                diff += abs(Color.blue(pa) - Color.blue(pb)).toLong()
            }
        }
        sa.recycle()
        sb.recycle()
        val maxDiff = n.toLong() * n * 3 * 255
        return 1.0 - diff.toDouble() / maxDiff
    }

    // ---------------------------------------------------------------------
    // Macro engine
    // ---------------------------------------------------------------------

    private fun startMacro() {
        if (running) return
        editMode = false
        hideMarkers() // markers must be gone so they aren't tapped or screenshotted
        steps = ConfigStore.loadSteps(this)
        loopCount = ConfigStore.loadLoopCount(this)
        if (steps.isEmpty()) {
            toast("No steps configured")
            return
        }
        currentIndex = 0
        currentLoop = 0
        running = true
        toast("AutoTapper started")
        scheduleNext(0)
    }

    private fun stopMacro() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        toast("AutoTapper stopped")
    }

    private fun scheduleNext(delayMs: Long) {
        if (!running) return
        handler.postDelayed({ runStep() }, delayMs)
    }

    private fun runStep() {
        if (!running) return

        if (currentIndex >= steps.size) {
            currentIndex = 0
            currentLoop++
            if (loopCount != 0 && currentLoop >= loopCount) {
                stopMacro()
                return
            }
        }

        val step = steps[currentIndex]
        if (step.condImage == null) {
            currentIndex++
            performTap(step) { scheduleNext(step.postDelayMs) }
        } else {
            checkConditionThenAct(step)
        }
    }

    /** For image-conditional steps: tap only once the image matches; else re-check. */
    private fun checkConditionThenAct(step: TapStep) {
        val ref = loadRef(step.condImage)
        if (ref == null) {
            // Reference missing — treat as a plain tap so the macro doesn't stall.
            currentIndex++
            performTap(step) { scheduleNext(step.postDelayMs) }
            return
        }
        captureScreen(
            onBitmap = { bmp ->
                val patch = crop(bmp, step.condCenterX, step.condCenterY, PATCH_SIZE)
                bmp.recycle()
                val sim = if (patch != null) similarity(patch, ref) else 0.0
                patch?.recycle()
                ref.recycle()
                if (!running) return@captureScreen
                if (sim >= step.threshold) {
                    currentIndex++
                    performTap(step) { scheduleNext(step.postDelayMs) }
                } else {
                    scheduleNext(RECHECK_MS) // keep waiting on the same step
                }
            },
            onError = {
                ref.recycle()
                scheduleNext(RECHECK_MS)
            }
        )
    }

    private fun loadRef(name: String?): Bitmap? {
        if (name == null) return null
        val f = File(filesDir, name)
        if (!f.exists()) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
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
        if (!dispatched) onDone()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
