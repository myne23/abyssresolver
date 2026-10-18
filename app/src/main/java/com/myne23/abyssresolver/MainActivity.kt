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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(ResolverService.DIAG_PREFS, MODE_PRIVATE)
        val stage = prefs.getString("stage", null) ?: "sin_eventos"
        val detail = prefs.getString("detail", "Todavía no se recibió ninguna petición.")
        val timestamp = prefs.getLong("time", 0L)
        val timeText = if (timestamp > 0L) DateFormat.getDateTimeInstance().format(Date(timestamp)) else "Nunca"

        val title = TextView(this).apply {
            textSize = 23f
            text = "Kino Resolvers"
        }
        val body = TextView(this).apply {
            textSize = 16f
            text = getString(R.string.status_title) + "\n\n" + getString(R.string.status_body) +
                "\n\nPaquete: com.mmilew.kinoresolvers" +
                "\nProtocolo disponible: Abyss (experimental)" +
                "\nMega: todavía no implementado" +
                "\n\nDIAGNÓSTICO DE LA ÚLTIMA PETICIÓN" +
                "\nEstado: $stage" +
                "\nDetalle: $detail" +
                "\nHora: $timeText" +
                "\n\nAbrí esta pantalla de nuevo después de probar Abyss para ver el último estado guardado." +
                "\nLa ausencia de eventos indica que Kino no llegó al servicio, o que la versión instalada no contiene estos diagnósticos."
            setTextIsSelectable(true)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(36, 48, 36, 36)
            addView(title)
            addView(body)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }
}
