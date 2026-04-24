package com.sentinelx.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class RecoveryGuideActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(0xFF050A12.toInt())

        val root = ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        fun makeText(text: String, sizeSp: Float, colorHex: Long, bold: Boolean = false) =
            TextView(this).apply {
                this.text = text
                textSize = sizeSp
                setTextColor(colorHex.toInt())
                if (bold) setTypeface(null, Typeface.BOLD)
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 24 }
            }

        container.addView(makeText("🚨 Act Now — You Overrode a HIGH RISK Alert", 18f, 0xFFF87171, true))
        container.addView(makeText("Do these steps in the next 10 minutes:", 13f, 0xFF94A3B8))

        val steps = listOf(
            "1️⃣  Call your bank immediately → press 0 for fraud department",
            "2️⃣  Tell them: 'I may have been scammed — freeze my UPI immediately'",
            "3️⃣  Note the UPI transaction ID from your payment app",
            "4️⃣  File complaint at cybercrime.gov.in or call 1930",
            "5️⃣  Screenshot your payment history now as evidence",
            "6️⃣  Do NOT call the original number back — it is the scammer",
            "7️⃣  Tell a trusted family member what happened",
        )
        steps.forEach { container.addView(makeText(it, 14f, 0xFFE2E8F0)) }

        container.addView(makeText("📞 Key Numbers:", 13f, 0xFF94A3B8, true))
        container.addView(makeText("• Cyber Crime Helpline: 1930\n• RBI Ombudsman: 14448\n• NPCI UPI Dispute: 1800-120-1740", 13f, 0xFF60A5FA))

        val btn = android.widget.Button(this).apply {
            text = "I Have Done These Steps"
            setBackgroundColor(0xFF065F46.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { finish() }
        }
        container.addView(btn)
        root.addView(container)
        setContentView(root)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, RecoveryGuideActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
