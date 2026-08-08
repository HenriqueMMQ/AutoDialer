package com.dialerapp

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        val prefs = getSharedPreferences("dialer_state", MODE_PRIVATE)
        val seekDelay = findViewById<SeekBar>(R.id.seekDelay)
        val txtDelayValue = findViewById<TextView>(R.id.txtDelayValue)

        val savedDelay = prefs.getInt("autoCallDelay", 5)
        seekDelay.progress = savedDelay
        txtDelayValue.text = "${savedDelay}s"

        seekDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                txtDelayValue.text = "${progress}s"
                prefs.edit().putInt("autoCallDelay", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }
}
