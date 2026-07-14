package com.example.autotapper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
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
import android.widget.SeekBar
import android.widget.TextView
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
    private var bubble: View? = null

    // --- on-screen draggable tap markers (edit mode) ---
    private val markerViews = mutableListOf<View>()
    private var editMode = false

    // --- macro run state (two parallel loops) ---
    private var running = false
    private var tapSteps: List<TapStep> = emptyList()     // plain taps: looped continuously
    private var watchSteps: List<TapStep> = emptyList()   // image steps: watched in parallel
    private val refCache = mutableMapOf<String, Bitmap>() // preloaded reference images
    private val lastWatchTap = mutableMapOf<Int, Long>()  // per-watcher cooldown
    private var loopCount = 0          // passes over tapSteps; 0 == forever
    private var tapIndex = 0
    private var tapLoop = 0
    private var tapActive = false

    companion object {
        private const val RECHECK_MS = 1100L      // re-check interval (screenshot is rate-limited ~1s)
        private const val CAPTURE_SETTLE_MS = 200L // let the capture scrim disappear before screenshotting
        private const val MATCH_GRID = 16         // downscale size used when comparing images
        private const val MARKER_SIZE_DP = 48     // diameter of the draggable tap markers
        private const val DRAG_SLOP_DP = 8        // movement before a touch counts as a drag
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
        removeRegionOverlay()
        removeBubble()
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
        panel.findViewById<Button>(R.id.btnHide).setOnClickListener { collapseToBubble() }

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
    // Collapse the panel into a small draggable bubble (tap to reopen)
    // ---------------------------------------------------------------------

    private fun collapseToBubble() {
        hideMarkers()
        editMode = false
        removeControlPanel()
        showBubble()
    }

    private fun showBubble() {
        if (bubble != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.bubble_view, null)
        val size = dp(52)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        val slop = dp(DRAG_SLOP_DP)
        var downX = 0f
        var downY = 0f
        var startLpX = 0
        var startLpY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startLpX = lp.x; startLpY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = startLpX + dx.toInt()
                        lp.y = startLpY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {           // a tap re-opens the panel
                        removeBubble()
                        showControlPanel()
                    }
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(view, lp) }
        bubble = view
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
    }

    // ---------------------------------------------------------------------
    // Draggable on-screen tap markers (the "popular app" way to set points)
    // ---------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toggleEditMode() {
        editMode = !editMode
        if (editMode) {
            showMarkers()
            toast("โหมดแก้ไข: ลากเพื่อย้าย • แตะวงกลมเพื่อตั้งความเร็ว/ลบ")
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
            if (step.condImage != null) {
                // Image-watch step: distinct colour + camera glyph.
                marker.setBackgroundResource(R.drawable.marker_circle_img)
                marker.text = "📷"
            } else {
                marker.text = (index + 1).toString()
            }
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

        marker.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startLpX = lp.x
                    startLpY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = startLpX + dx.toInt()
                        lp.y = startLpY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(marker, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        updateStepPosition(
                            index,
                            (lp.x + size / 2).toFloat(),
                            (lp.y + size / 2).toFloat()
                        )
                    } else {
                        showStepSettingsDialog(index) // a tap opens per-point settings
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** Per-point settings via sliders: tap speed, human-like randomness, jitter. */
    private fun showStepSettingsDialog(index: Int) {
        val steps = ConfigStore.loadSteps(this)
        if (index !in steps.indices) return
        val step = steps[index]

        val themed = android.view.ContextThemeWrapper(
            this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        )
        val view = LayoutInflater.from(themed).inflate(R.layout.dialog_step_settings, null)
        val seekDelay = view.findViewById<SeekBar>(R.id.seekDelay)
        val seekRandom = view.findViewById<SeekBar>(R.id.seekRandom)
        val seekJitter = view.findViewById<SeekBar>(R.id.seekJitter)
        val labelDelay = view.findViewById<TextView>(R.id.labelDelay)
        val labelRandom = view.findViewById<TextView>(R.id.labelRandom)
        val labelJitter = view.findViewById<TextView>(R.id.labelJitter)

        seekDelay.progress = step.postDelayMs.toInt().coerceIn(0, seekDelay.max)
        seekRandom.progress = step.randomMs.toInt().coerceIn(0, seekRandom.max)
        seekJitter.progress = step.posJitter.coerceIn(0, seekJitter.max)
        labelDelay.text = "เวลารอหลังกด: ${seekDelay.progress} ms"
        labelRandom.text = "สุ่มบวกเพิ่ม (เลียนแบบคน): ${seekRandom.progress} ms"
        labelJitter.text = "สุ่มตำแหน่งนิ้ว: ${seekJitter.progress} px"

        seekDelay.onProgress { labelDelay.text = "เวลารอหลังกด: $it ms" }
        seekRandom.onProgress { labelRandom.text = "สุ่มบวกเพิ่ม (เลียนแบบคน): $it ms" }
        seekJitter.onProgress { labelJitter.text = "สุ่มตำแหน่งนิ้ว: $it px" }

        val dialog = AlertDialog.Builder(themed)
            .setTitle("⚙️ ตั้งค่าจุดที่ ${index + 1}")
            .setView(view)
            .setPositiveButton("บันทึก") { _, _ ->
                val list = ConfigStore.loadSteps(this)
                if (index in list.indices) {
                    list[index] = list[index].copy(
                        postDelayMs = seekDelay.progress.toLong(),
                        randomMs = seekRandom.progress.toLong(),
                        posJitter = seekJitter.progress
                    )
                    ConfigStore.saveSteps(this, list)
                    showMarkers()
                    toast("บันทึกจุดที่ ${index + 1} แล้ว")
                }
            }
            .setNegativeButton("ยกเลิก", null)
            .setNeutralButton("🗑 ลบจุดนี้") { _, _ -> deleteStep(index) }
            .create()
        // An accessibility service can only show a dialog via an overlay window.
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
    }

    private fun SeekBar.onProgress(update: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) =
                update(progress)

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
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
     * Flow to build an image-conditional step:
     *   1) DRAG a box around the button/area to watch -> screenshot + save it
     *   2) tap where the action should happen          -> store as the tap target
     */
    private fun captureImageConditionStep() {
        editMode = false
        hideMarkers() // keep markers out of the reference screenshot
        toast("1) ลากคลุมกรอบรอบ 'ปุ่ม/ภาพ' ที่จะรอ")
        showRegionSelect { rect ->
            // Let the selection overlay clear, then grab the screen and crop the box.
            handler.postDelayed({
                captureScreen(onBitmap = { bmp ->
                    val patch = cropRect(bmp, rect.left, rect.top, rect.width(), rect.height())
                    bmp.recycle()
                    if (patch == null) {
                        toast("เลือกบริเวณไม่ได้ ลองใหม่อีกครั้ง")
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
                                condImage = fname,
                                condLeft = rect.left, condTop = rect.top,
                                condW = rect.width(), condH = rect.height()
                            )
                        )
                        toast("เพิ่มแล้ว: รอภาพในกรอบ → กด (${tapX.toInt()}, ${tapY.toInt()})")
                    }
                }, onError = { toast("ถ่ายภาพหน้าจอไม่ได้") })
            }, CAPTURE_SETTLE_MS)
        }
    }

    private var regionOverlay: View? = null

    private fun showRegionSelect(onSelected: (android.graphics.Rect) -> Unit) {
        removeRegionOverlay()
        val view = RegionSelectView(this) { rect ->
            removeRegionOverlay()
            onSelected(rect)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        runCatching { windowManager.addView(view, lp) }
        regionOverlay = view
    }

    private fun removeRegionOverlay() {
        regionOverlay?.let { runCatching { windowManager.removeView(it) } }
        regionOverlay = null
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

    /** Crop an arbitrary rectangle, clamped to the bitmap bounds. */
    private fun cropRect(src: Bitmap, left: Int, top: Int, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val l = left.coerceIn(0, (src.width - 1).coerceAtLeast(0))
        val t = top.coerceIn(0, (src.height - 1).coerceAtLeast(0))
        val ww = w.coerceAtMost(src.width - l)
        val hh = h.coerceAtMost(src.height - t)
        if (ww <= 0 || hh <= 0) return null
        return Bitmap.createBitmap(src, l, t, ww, hh)
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

        val all = ConfigStore.loadSteps(this)
        tapSteps = all.filter { it.condImage == null }
        watchSteps = all.filter { it.condImage != null }
        loopCount = ConfigStore.loadLoopCount(this)
        if (tapSteps.isEmpty() && watchSteps.isEmpty()) {
            toast("ยังไม่มีจุด")
            return
        }

        // Preload every watcher's reference image once.
        refCache.values.forEach { runCatching { it.recycle() } }
        refCache.clear()
        lastWatchTap.clear()
        watchSteps.forEach { w ->
            val name = w.condImage ?: return@forEach
            if (!refCache.containsKey(name)) loadRef(name)?.let { refCache[name] = it }
        }

        tapIndex = 0
        tapLoop = 0
        running = true
        toast("เริ่มทำงาน")

        // Loop 1: tap points, continuously.
        tapActive = tapSteps.isNotEmpty()
        if (tapActive) scheduleTap(0)
        // Loop 2 (parallel): watch for images and tap them when they appear.
        if (watchSteps.isNotEmpty()) scheduleWatch(300)
    }

    private fun stopMacro() {
        if (!running) return
        running = false
        tapActive = false
        handler.removeCallbacksAndMessages(null)
        refCache.values.forEach { runCatching { it.recycle() } }
        refCache.clear()
        toast("หยุดแล้ว")
    }

    /** Wait time for a step, plus a random 0..randomMs to look human. */
    private fun effectiveDelay(step: TapStep): Long {
        val extra = if (step.randomMs > 0) (0..step.randomMs).random() else 0L
        return step.postDelayMs + extra
    }

    // --- Loop 1: continuous tapping of the plain points ---

    private fun scheduleTap(delayMs: Long) {
        if (!running || !tapActive) return
        handler.postDelayed({ runTapStep() }, delayMs)
    }

    private fun runTapStep() {
        if (!running || !tapActive) return
        if (tapSteps.isEmpty()) { tapActive = false; return }

        if (tapIndex >= tapSteps.size) {
            tapIndex = 0
            tapLoop++
            if (loopCount != 0 && tapLoop >= loopCount) {
                tapActive = false
                if (watchSteps.isEmpty()) stopMacro() // nothing left running
                return
            }
        }

        val step = tapSteps[tapIndex]
        tapIndex++
        performTap(step) { scheduleTap(effectiveDelay(step)) }
    }

    // --- Loop 2: watch regions for images, tap their target when seen ---

    private fun scheduleWatch(delayMs: Long) {
        if (!running || watchSteps.isEmpty()) return
        handler.postDelayed({ runWatch() }, delayMs)
    }

    private fun runWatch() {
        if (!running || watchSteps.isEmpty()) return
        captureScreen(
            onBitmap = { bmp ->
                if (!running) { bmp.recycle(); return@captureScreen }
                val now = android.os.SystemClock.uptimeMillis()
                watchSteps.forEachIndexed { i, w ->
                    val ref = w.condImage?.let { refCache[it] } ?: return@forEachIndexed
                    val patch = cropRect(bmp, w.condLeft, w.condTop, w.condW, w.condH)
                        ?: return@forEachIndexed
                    val sim = similarity(patch, ref)
                    patch.recycle()
                    if (sim >= w.threshold) {
                        // Don't re-tap the same watcher faster than its own wait time.
                        val last = lastWatchTap[i] ?: 0L
                        if (now - last >= maxOf(RECHECK_MS, w.postDelayMs)) {
                            lastWatchTap[i] = now
                            performTap(w) {}
                        }
                    }
                }
                bmp.recycle()
                scheduleWatch(RECHECK_MS)
            },
            onError = { scheduleWatch(RECHECK_MS) }
        )
    }

    private fun loadRef(name: String?): Bitmap? {
        if (name == null) return null
        val f = File(filesDir, name)
        if (!f.exists()) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    private fun performTap(step: TapStep, onDone: () -> Unit) {
        // Apply a random ±posJitter offset so taps don't land on the exact same pixel.
        val tapX = if (step.posJitter > 0) step.x + (-step.posJitter..step.posJitter).random() else step.x
        val tapY = if (step.posJitter > 0) step.y + (-step.posJitter..step.posJitter).random() else step.y
        val path = Path().apply { moveTo(tapX, tapY) }
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
