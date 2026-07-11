package com.example.autotapper.data

import android.content.Context
import com.example.autotapper.model.TapStep
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny persistence layer backed by SharedPreferences. The step list is stored
 * as a JSON array so the main screen and the AccessibilityService can share the
 * same configuration without a database or a bound-service handshake.
 */
object ConfigStore {
    private const val PREFS = "autotapper_prefs"
    private const val KEY_STEPS = "steps"
    private const val KEY_LOOP = "loop_count"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadSteps(ctx: Context): MutableList<TapStep> {
        val raw = prefs(ctx).getString(KEY_STEPS, null) ?: return mutableListOf()
        val out = mutableListOf<TapStep>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                TapStep(
                    x = o.getDouble("x").toFloat(),
                    y = o.getDouble("y").toFloat(),
                    postDelayMs = o.getLong("delay"),
                    tapDurationMs = o.optLong("dur", 50L)
                )
            )
        }
        return out
    }

    fun saveSteps(ctx: Context, steps: List<TapStep>) {
        val arr = JSONArray()
        for (s in steps) {
            arr.put(
                JSONObject()
                    .put("x", s.x.toDouble())
                    .put("y", s.y.toDouble())
                    .put("delay", s.postDelayMs)
                    .put("dur", s.tapDurationMs)
            )
        }
        prefs(ctx).edit().putString(KEY_STEPS, arr.toString()).apply()
    }

    fun loadLoopCount(ctx: Context): Int = prefs(ctx).getInt(KEY_LOOP, 0)

    fun saveLoopCount(ctx: Context, count: Int) {
        prefs(ctx).edit().putInt(KEY_LOOP, count).apply()
    }

    /** Append one step and persist. Used by the capture overlay. */
    fun addStep(ctx: Context, step: TapStep) {
        val list = loadSteps(ctx)
        list.add(step)
        saveSteps(ctx, list)
    }
}
