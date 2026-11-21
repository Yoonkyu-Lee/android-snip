package com.androidsnip

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Debug-only test screen. Every pixel encodes its own absolute screen coordinate:
 *   R = x & 0xFF, G = y & 0xFF, B = (x >> 8) << 4 | (y >> 8)
 * so any crop can be decoded back to its exact on-screen position.
 *   adb shell am start -f 0x10008000 -n com.androidsnip/.PatternActivity [--es orient land|port] [--ez immersive true] [--ez popup true]
 * (never use am start -S: force-stopping the app disables its accessibility service)
 */
class PatternActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = when (intent.getStringExtra("orient")) {
            "land" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "port" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.setDecorFitsSystemWindows(false)
        setContentView(PatternView(this))
        if (intent.getBooleanExtra("popup", false)) {
            // Transient UI probe: is a popup menu still on screen when the capture happens?
            window.decorView.postDelayed({
                android.widget.PopupMenu(this, window.decorView.findViewById(android.R.id.content)).apply {
                    menu.add("Popup item one"); menu.add("Popup item two"); menu.add("Popup item three")
                    show()
                }
            }, 800)
        }
        if (intent.getBooleanExtra("immersive", false)) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    class PatternView(c: Context) : View(c) {
        private var bmp: Bitmap? = null
        private val loc = IntArray(2)
        override fun onDraw(canvas: Canvas) {
            val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            var bm = bmp
            if (bm == null || bm.width != b.width() || bm.height != b.height()) {
                val w = b.width(); val h = b.height()
                val px = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    px[y * w + x] = (0xFF shl 24) or ((x and 0xFF) shl 16) or ((y and 0xFF) shl 8) or
                            (((x shr 8) shl 4) or (y shr 8))
                }
                bm = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                bmp = bm
            }
            getLocationOnScreen(loc)
            canvas.drawBitmap(bm!!, -loc[0].toFloat(), -loc[1].toFloat(), null)
        }
    }
}
