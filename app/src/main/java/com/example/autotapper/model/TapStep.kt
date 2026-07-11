package com.example.autotapper.model

/**
 * A single action in the macro: tap at (x, y), then wait [postDelayMs] before
 * the next step. Coordinates are absolute screen pixels.
 *
 * @param tapDurationMs how long the finger "stays down" for the tap.
 */
data class TapStep(
    val x: Float,
    val y: Float,
    val postDelayMs: Long,
    val tapDurationMs: Long = 50L
)
