package com.sentinelx.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.sentinelx.core.BlocklistManager

class BlocklistAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra("number") ?: return
        val manager = BlocklistManager(context)
        when (intent.action) {
            "com.sentinelx.BLOCK_AND_REPORT" -> {
                manager.blockNumber(number, com.sentinelx.core.AddedReason.MANUAL)
                Toast.makeText(context, "Number reported to community", Toast.LENGTH_SHORT).show()
            }
            "com.sentinelx.MARK_SAFE" -> {
                manager.unblockNumber(number)
                Toast.makeText(context, "Number removed from blocklist", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
