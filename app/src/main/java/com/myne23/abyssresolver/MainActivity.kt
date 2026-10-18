package com.myne23.abyssresolver

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            textSize = 17f
            setPadding(36, 48, 36, 36)
            text = getString(R.string.status_title) + "\n\n" + getString(R.string.status_body) +
                "\n\nPackage: com.myne23.abyssresolver\nProtocols: abyss, mega\n\nDevelopment build 0.2.0 — MEGA support requires playback testing."
        }
        setContentView(text)
    }
}
