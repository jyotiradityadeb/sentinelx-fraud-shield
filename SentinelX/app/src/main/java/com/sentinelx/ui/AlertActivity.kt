package com.sentinelx.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sentinelx.R

class AlertActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alert)

        val score = intent.getIntExtra(EXTRA_SCORE, 0)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "HIGH_RISK" }
        val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty().ifBlank { "Suspicious behavior detected." }
        val signals = intent.getStringExtra(EXTRA_SIGNALS).orEmpty().ifBlank { "-" }

        findViewById<TextView>(R.id.alertScore).text = "Score: $score/120"
        findViewById<TextView>(R.id.alertLabel).text = "Risk: $label"
        findViewById<TextView>(R.id.alertMessage).text = prompt
        findViewById<TextView>(R.id.alertSignals).text = "Signals: $signals"
        findViewById<Button>(R.id.btnAlertClose).setOnClickListener { finish() }
    }

    companion object {
        private const val EXTRA_SCORE = "score"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_SIGNALS = "signals"

        fun start(
            context: Context,
            score: Int,
            label: String,
            prompt: String,
            signals: String,
        ) {
            val intent = Intent(context, AlertActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_SCORE, score)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_SIGNALS, signals)
            }
            context.startActivity(intent)
        }
    }
}
