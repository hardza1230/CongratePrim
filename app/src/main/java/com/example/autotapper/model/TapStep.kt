package com.example.autotapper.model

/**
 * A single action in the macro.
 *
 * Two kinds of step:
 *  - **Plain tap** ([condImage] == null): tap (x, y), then wait [postDelayMs].
 *  - **Image-conditional tap** ([condImage] != null): first look at the screen
 *    around ([condCenterX], [condCenterY]); only when the saved reference image
 *    matches there (similarity >= [threshold]) do we tap (x, y). Until it
 *    matches, the engine keeps re-checking — i.e. "wait for this to appear,
 *    then tap here".
 *
 * @param condImage filename (in filesDir) of the reference image patch, or null.
 */
data class TapStep(
    val x: Float,
    val y: Float,
    val postDelayMs: Long,
    val tapDurationMs: Long = 50L,
    val condImage: String? = null,
    val condCenterX: Float = 0f,
    val condCenterY: Float = 0f,
    val threshold: Double = 0.90
)
