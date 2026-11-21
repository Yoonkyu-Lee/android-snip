package com.androidsnip

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Phase 1 PoC. Generates a PNG unique to this run (nonce encoded as a bit grid
 * plus a random background) and puts it on the clipboard as a content:// URI.
 *   adb shell am start -n com.androidsnip/.ClipTestActivity --es nonce <hex>
 */
class ClipTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nonce = intent.getStringExtra("nonce") ?: Random.nextLong().toULong().toString(16)
        val tv = TextView(this).apply { textSize = 22f; setPadding(40, 40, 40, 40) }
        setContentView(tv)

        val bmp = render(nonce)
        val dir = ShotProvider.dir(this)
        dir.listFiles()?.forEach { it.delete() } // keep only the newest test image
        val file = File(dir, "poc_$nonce.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = ShotProvider.uriFor(file)
        val clip = ClipData.newUri(contentResolver, "AndroidSnip", uri)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)

        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        Log.i("AndroidSnip", "POC_COPIED nonce=$nonce uri=$uri mime=${clip.description.getMimeType(0)} size=${file.length()} sha256=$sha")
        tv.text = "Copied test image\nnonce=$nonce\n${clip.description.getMimeType(0)}\n$uri"
    }

    private fun render(nonce: String): Bitmap {
        val w = 480; val h = 270
        val rnd = Random(nonce.hashCode())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(rnd.nextInt(64, 192), rnd.nextInt(64, 192), rnd.nextInt(64, 192)))
        val p = Paint()
        // 16x6 grid of random colored cells: practically unique per nonce
        val cw = w / 16f; val ch = (h - 60) / 6f
        for (y in 0 until 6) for (x in 0 until 16) {
            p.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            c.drawRect(x * cw, 60 + y * ch, (x + 1) * cw, 60 + (y + 1) * ch, p)
        }
        p.color = Color.BLACK; p.textSize = 36f; p.isAntiAlias = true
        c.drawText("AndroidSnip $nonce", 12f, 44f, p)
        return bmp
    }
}
