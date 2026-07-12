package com.example.autotapper.model

/**
 * A single action in the macro.
 *
 *  - **Plain tap** ([condImage] == null): tap (x, y), then wait [postDelayMs].
 *  - **Image-conditional tap** ([condImage] != null): watch only the rectangle
 *    ([condLeft], [condTop], [condW]×[condH]) — e.g. a single button — and tap
 *    (x, y) only once the saved reference image matches there (similarity >=
 *    [threshold]). Until it matches, the engine keeps re-checking. It never
 *    compares the whole screen, just that region.
 *
 * @param condImage filename (in filesDir) of the reference image, or null.
 */
data class TapStep(
    val x: Float,
    val y: Float,
    val postDelayMs: Long,
    val tapDurationMs: Long = 50L,
    val condImage: String? = null,
    val condLeft: Int = 0,
    val condTop: Int = 0,
    val condW: Int = 0,
    val condH: Int = 0,
    val threshold: Double = 0.90
)
