package com.androidsnip

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.File

/**
 * Takes the screenshot (AccessibilityService.takeScreenshot), shows the selection
 * overlay on top of the frozen frame, crops, writes a PNG and puts its content://
 * URI on the clipboard. The screenshot is always taken before any of our own
 * windows exist, so the overlay can never be in the image.
 */
class SnipService : AccessibilityService() {

    companion object {
        const val TAG = "AndroidSnip"
        const val PREFS = "snip"
        const val KEY_ADJUST = "adjust_mode"
        /** Wait for the launching gesture's feedback/animation to clear the screen. */
        const val ACTIVITY_DELAY_MS = 250L
        /** Wait for the notification shade collapse animation. */
        const val TILE_DELAY_MS = 700L
        private const val KEEP_FILES = 3

        @Volatile
        var instance: SnipService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var overlay: SelectionView? = null
    private var busy = false
    private var requestedAt = 0L

    override fun onServiceConnected() {
        instance = this
        prune(keep = 1)
        Log.i(TAG, "service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        dismissOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        dismissOverlay()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Called only from explicit user actions (OHO+, launcher, shortcut, QS tile). */
    fun requestCapture(delayMs: Long) {
        main.post {
            if (busy) return@post
            busy = true
            requestedAt = SystemClock.uptimeMillis()
            main.postDelayed({ capture(retry = true) }, delayMs)
        }
    }

    private fun capture(retry: Boolean) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val shot = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle()
                result.hardwareBuffer.close()
                if (shot == null) return fail("캡처 실패")
                Log.i(TAG, "screenshot ${shot.width}x${shot.height} cs=${result.colorSpace.name} " +
                        "in ${SystemClock.uptimeMillis() - requestedAt}ms")
                showOverlay(shot)
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "takeScreenshot failed: $errorCode")
                if (retry && errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    main.postDelayed({ capture(retry = false) }, 1000)
                } else {
                    fail("캡처 실패 ($errorCode)")
                }
            }
        })
    }

    private fun showOverlay(shot: Bitmap) {
        val wm = getSystemService(WindowManager::class.java)
        val adjust = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ADJUST, false)
        val view = SelectionView(this, shot, wm.maximumWindowMetrics.bounds, adjust) { rect ->
            dismissOverlay()
            if (rect == null) {
                shot.recycle()
                busy = false
                Log.i(TAG, "selection cancelled")
            } else {
                copyRegion(shot, rect)
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            windowAnimations = 0
            title = "AndroidSnipOverlay"
        }
        wm.addView(view, lp)
        overlay = view
    }

    private fun dismissOverlay() {
        overlay?.let {
            try { getSystemService(WindowManager::class.java).removeViewImmediate(it) } catch (_: Exception) {}
        }
        overlay = null
    }

    /** rect is in screenshot pixel coordinates. */
    private fun copyRegion(shot: Bitmap, rect: Rect) {
        Thread {
            try {
                val crop = Bitmap.createBitmap(shot, rect.left, rect.top, rect.width(), rect.height())
                val file = File(ShotProvider.dir(this), "snip_${System.currentTimeMillis()}.png")
                file.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (crop !== shot) crop.recycle()
                shot.recycle()
                main.post {
                    val uri = ShotProvider.uriFor(file)
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newUri(contentResolver, "AndroidSnip", uri))
                    Log.i(TAG, "COPIED ${rect.width()}x${rect.height()} at ${rect.left},${rect.top} " +
                            "size=${file.length()} uri=$uri")
                    // No own toast: One UI already shows "클립보드에 복사되었어요."
                    busy = false
                    prune(keep = KEEP_FILES)
                }
            } catch (e: Exception) {
                Log.e(TAG, "copy failed", e)
                main.post { fail("복사 실패") }
            }
        }.start()
    }

    /** Temp file cleanup: keep only the newest few (the clipboard points at the newest). */
    private fun prune(keep: Int) {
        val files = ShotProvider.dir(this).listFiles() ?: return
        files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    private fun fail(msg: String) {
        busy = false
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
