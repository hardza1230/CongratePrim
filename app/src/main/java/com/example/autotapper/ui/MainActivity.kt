package com.example.autotapper.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.autotapper.R
import com.example.autotapper.data.ConfigStore
import com.example.autotapper.model.TapStep
import com.example.autotapper.service.AutoTapService

/**
 * Configuration screen. It never taps anything itself — it only edits the step
 * list and loop count that [AutoTapService] reads when you press Start on the
 * floating panel.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var loopInput: EditText
    private lateinit var stepContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        loopInput = findViewById(R.id.loopInput)
        stepContainer = findViewById(R.id.stepContainer)

        findViewById<Button>(R.id.btnEnableService).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnAddStep).setOnClickListener {
            saveAll()
            ConfigStore.addStep(this, TapStep(x = 540f, y = 1200f, postDelayMs = 1000L))
            reloadSteps()
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        loopInput.setText(ConfigStore.loadLoopCount(this).toString())
        reloadSteps()
    }

    override fun onPause() {
        saveAll()
        super.onPause()
    }

    // ------------------------------------------------------------------
    // Step list rendering
    // ------------------------------------------------------------------

    private fun reloadSteps() {
        stepContainer.removeAllViews()
        val steps = ConfigStore.loadSteps(this)
        for (step in steps) addRow(step)
    }

    private fun addRow(step: TapStep) {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_step, stepContainer, false)
        // Keep the original step on the row so image-condition data (which the
        // UI doesn't edit) survives a save.
        row.tag = step
        if (step.condImage != null) {
            row.findViewById<TextView>(R.id.condLabel).visibility = View.VISIBLE
        }
        row.findViewById<EditText>(R.id.inputX).setText(step.x.toInt().toString())
        row.findViewById<EditText>(R.id.inputY).setText(step.y.toInt().toString())
        row.findViewById<EditText>(R.id.inputDelay).setText(step.postDelayMs.toString())
        row.findViewById<Button>(R.id.btnDelete).setOnClickListener {
            stepContainer.removeView(row)
            saveAll()
        }
        stepContainer.addView(row)
    }

    /** Read every row + the loop field back into persistent storage. */
    private fun saveAll() {
        val steps = mutableListOf<TapStep>()
        for (i in 0 until stepContainer.childCount) {
            val row = stepContainer.getChildAt(i)
            val x = row.findViewById<EditText>(R.id.inputX).text.toString().toFloatOrNull() ?: continue
            val y = row.findViewById<EditText>(R.id.inputY).text.toString().toFloatOrNull() ?: continue
            val delay = row.findViewById<EditText>(R.id.inputDelay).text.toString().toLongOrNull() ?: 1000L
            val orig = row.tag as? TapStep
            steps.add(
                TapStep(
                    x = x, y = y, postDelayMs = delay,
                    condImage = orig?.condImage,
                    condCenterX = orig?.condCenterX ?: 0f,
                    condCenterY = orig?.condCenterY ?: 0f,
                    threshold = orig?.threshold ?: 0.90
                )
            )
        }
        ConfigStore.saveSteps(this, steps)
        ConfigStore.saveLoopCount(this, loopInput.text.toString().toIntOrNull() ?: 0)
    }

    // ------------------------------------------------------------------
    // Accessibility service status
    // ------------------------------------------------------------------

    private fun updateServiceStatus() {
        if (isServiceEnabled()) {
            statusText.text = getString(R.string.service_enabled)
        } else {
            statusText.text = getString(R.string.service_disabled)
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${AutoTapService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
