package com.example.autotapper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import com.example.autotapper.R

/**
 * Captures the screen with MediaProjection — the mechanism OEM ROMs like
 * ColorOS/OPPO actually allow (AccessibilityService.takeScreenshot is blocked
 * there). Runs as a foreground service (required for media projection) and
 * mirrors the display into an ImageReader so [captureBitmap] can grab the
 * latest frame on demand.
 *
 * Started by MainActivity after the user grants the projection permission.
 */
class ScreenCaptureService : Service() {

    companion object {
        @Volatile
        var instance: ScreenCaptureService? = null
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        val isReady: Boolean get() = instance?.imageReader != null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var density = 0

    private val thread = HandlerThread("screen-capture").also { it.start() }
    private val handler = Handler(thread.looper)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
        width = bounds.width()
        height = bounds.height()
        density = resources.configuration.densityDpi

        val mpm = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mpm.getMediaProjection(code, data)?.apply {
            // Android 14 requires a callback registered before creating displays.
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { cleanup() }
            }, handler)
        }
        if (mediaProjection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "AutoTapCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
        instance = this
        return START_STICKY
    }

    /** Grab the most recent frame as an ARGB bitmap (or null if unavailable). */
    fun captureBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            }
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "screen_capture"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Screen capture", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("AutoTapper")
            .setContentText("กำลังจับภาพหน้าจอสำหรับโหมด 'รอภาพ'")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        startForeground(
            1, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun cleanup() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        thread.quitSafely()
        super.onDestroy()
    }
}
