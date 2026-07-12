package com.example.autotapper.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * A full-screen overlay you drag across to select a rectangle (e.g. around a
 * button). It draws the selection while you drag, and reports the chosen Rect
 * (in screen pixels) on finger-up. Used to tell the app which region of the
 * screen to watch for an image — never the whole screen.
 */
class RegionSelectView(
    context: Context,
    private val onSelected: (Rect) -> Unit
) : View(context) {

    private val fill = Paint().apply {
        color = 0x3300B0FF
        style = Paint.Style.FILL
    }
    private val stroke = Paint().apply {
        color = Color.parseColor("#FF00B0FF")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                endX = event.x; endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x; endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val l = min(startX, endX).toInt()
                val t = min(startY, endY).toInt()
                val r = max(startX, endX).toInt()
                val b = max(startY, endY).toInt()
                invalidate()
                if (r - l > 12 && b - t > 12) onSelected(Rect(l, t, r, b))
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = min(startX, endX)
        val t = min(startY, endY)
        val r = max(startX, endX)
        val b = max(startY, endY)
        if (r - l > 2 && b - t > 2) {
            canvas.drawRect(l, t, r, b, fill)
            canvas.drawRect(l, t, r, b, stroke)
        }
    }
}
