package com.androidsnip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Entry point for One Hand Operation+, the launcher icon and the app shortcut.
 * Uses Theme.NoDisplay: it never draws, so it cannot appear in the capture.
 * It only asks the accessibility service to capture, then finishes immediately.
 */
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = SnipService.instance
        if (service != null) {
            service.requestCapture(SnipService.ACTIVITY_DELAY_MS)
        } else {
            Toast.makeText(this, "AndroidSnip 접근성 서비스를 켜 주세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
