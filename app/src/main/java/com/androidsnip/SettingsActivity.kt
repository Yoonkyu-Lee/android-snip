package com.androidsnip

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Minimal settings/status screen. Built in code to avoid layout/AndroidX deps. */
class SettingsActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences(SnipService.PREFS, Context.MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "AndroidSnip"; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, pad / 2) }
        col.addView(status)
        col.addView(Button(this).apply {
            text = "접근성 설정 열기"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(Switch(this).apply {
            text = "Adjust 모드 (드래그 후 영역 조정, 복사 버튼으로 확정)"
            textSize = 16f
            setPadding(0, pad, 0, pad)
            isChecked = prefs.getBoolean(SnipService.KEY_ADJUST, false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(SnipService.KEY_ADJUST, on).apply() }
        })
        col.addView(TextView(this).apply {
            textSize = 14f
            text = """
                기본은 Auto Copy: 드래그를 끝내면 즉시 클립보드에 복사됩니다.

                캡처 시작 방법
                • Good Lock › One Hand Operation+ › 핸들 동작 › "앱 실행" › "Snip 캡처"
                  (또는 "바로가기" › AndroidSnip "영역 캡처")
                • 빠른 설정 패널의 "Snip" 타일
                • 앱 서랍의 "Snip 캡처" 아이콘

                취소: 뒤로가기 또는 드래그 없이 탭
                보안 화면(FLAG_SECURE)은 검게 캡처됩니다.
            """.trimIndent()
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (SnipService.instance != null) "✅ 접근성 서비스 켜짐"
        else "⚠️ 접근성 서비스가 꺼져 있습니다. 설정 › 접근성 › 설치된 앱 › AndroidSnip 을 켜 주세요."
    }
}
