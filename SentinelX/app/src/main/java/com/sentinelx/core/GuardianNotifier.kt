package com.sentinelx.core

object GuardianNotifier {
    fun send(context: android.content.Context, score: Int, threatType: String, guardianMessage: String) {
        GuardianManager(context).alertGuardian(score, guardianMessage, threatType)
    }
}
