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
                "\n\nPackage: com.mmilew.kinoresolvers\nAvailable resolver: Abyss (experimental)\nPlanned: Mega\n\nDevelopment build — real playback not yet validated."
        }
        setContentView(text)
    }
}
