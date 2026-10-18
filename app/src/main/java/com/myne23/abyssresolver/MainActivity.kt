package com.myne23.abyssresolver

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var diagnosticBody: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            textSize = 23f
            text = "Kino Resolvers"
        }
        diagnosticBody = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "desconocida"
        }.getOrDefault("desconocida")
        val build = TextView(this).apply {
            textSize = 13f
            text = "Versión instalada: $versionName (diagnóstico)"
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(36, 48, 36, 36)
            addView(title)
            addView(build)
            addView(diagnosticBody)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        refreshDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        if (::diagnosticBody.isInitialized) refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        val prefs = getSharedPreferences(ResolverService.DIAG_PREFS, MODE_PRIVATE)
        val stage = prefs.getString("stage", null) ?: "sin_eventos"
        val detail = prefs.getString("detail", "Todavía no se recibió ninguna petición.")
        val timestamp = prefs.getLong("time", 0L)
        val timeText = if (timestamp > 0L) DateFormat.getDateTimeInstance().format(Date(timestamp)) else "Nunca"

        diagnosticBody.text = getString(R.string.status_title) + "\n\n" + getString(R.string.status_body) +
            "\n\nPaquete: com.mmilew.kinoresolvers" +
            "\nProtocolo disponible: Abyss (experimental)" +
            "\nMega: todavía no implementado" +
            "\n\nDIAGNÓSTICO DE LA ÚLTIMA PETICIÓN" +
            "\nEstado: $stage" +
            "\nDetalle: $detail" +
            "\nHora: $timeText" +
            "\n\nVolvé a esta pantalla después de probar Abyss; el diagnóstico se actualiza al volver." +
            "\nSi sigue en sin_eventos, Kino no llegó al servicio o la instalación no corresponde a esta compilación."
    }
}
