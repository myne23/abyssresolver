package com.myne23.abyssresolver

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var diagnostics: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
        }
        val title = TextView(this).apply {
            text = "Kino External Resolver — diagnóstico"
            textSize = 21f
        }
        val description = TextView(this).apply {
            text = "Los eventos del servicio aparecen aquí, incluso cuando Kino falla al iniciar la reproducción. Volvé a esta pantalla después de probar MEGA o Abyss."
            textSize = 14f
            setPadding(0, 12, 0, 12)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val refresh = Button(this).apply {
            text = "Actualizar logs"
            setOnClickListener { refreshDiagnostics() }
        }
        val clear = Button(this).apply {
            text = "Borrar logs"
            setOnClickListener {
                getSharedPreferences("resolver_diagnostics", MODE_PRIVATE).edit().remove("lines").apply()
                refreshDiagnostics()
            }
        }
        buttons.addView(refresh, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        diagnostics = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Todavía no hay eventos del resolutor."
        }
        val scroll = ScrollView(this).apply {
            addView(diagnostics, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(title)
        root.addView(description)
        root.addView(buttons)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        if (::diagnostics.isInitialized) refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        val lines = getSharedPreferences("resolver_diagnostics", MODE_PRIVATE).getString("lines", "") ?: ""
        diagnostics.text = if (lines.isBlank()) {
            "Todavía no hay eventos del resolutor.\n\nAbrí Kino y seleccioná MEGA o Abyss; luego volvé acá y tocá Actualizar logs."
        } else lines
    }
}
