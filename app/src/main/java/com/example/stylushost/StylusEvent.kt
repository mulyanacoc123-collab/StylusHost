package com.example.stylushost

import android.graphics.Color
import org.json.JSONObject

enum class ActionType { DOWN, MOVE, UP }

/** Koordinat x,y NORMALISASI (0..1) relatif terhadap frame video. pressure 0..1. */
data class StylusEvent(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val action: ActionType
)

sealed class StylusCommand {
    data class Point(val event: StylusEvent, val color: Int?) : StylusCommand()
    object Clear : StylusCommand()
    object Undo : StylusCommand()
}

/**
 * Format JSON dari client:
 *  {"type":"stylus","x":0.52,"y":0.31,"pressure":0.7,"action_type":"MOVE","color":"#FF0000"}
 *  {"type":"clear"}
 *  {"type":"undo"}
 * "type" opsional (default "stylus"); "color" opsional.
 */
object StylusParser {
    fun parse(text: String): StylusCommand? = try {
        val o = JSONObject(text)
        when (o.optString("type", "stylus").lowercase()) {
            "clear" -> StylusCommand.Clear
            "undo" -> StylusCommand.Undo
            "stylus" -> {
                val action = ActionType.valueOf(o.getString("action_type").uppercase())
                val event = StylusEvent(
                    x = o.getDouble("x").toFloat().coerceIn(0f, 1f),
                    y = o.getDouble("y").toFloat().coerceIn(0f, 1f),
                    pressure = o.optDouble("pressure", 0.5).toFloat().coerceIn(0f, 1f),
                    action = action
                )
                val color = o.optString("color", "").takeIf { it.isNotEmpty() }?.let {
                    try { Color.parseColor(it) } catch (e: IllegalArgumentException) { null }
                }
                StylusCommand.Point(event, color)
            }
            else -> null
        }
    } catch (e: Exception) {
        null // JSON rusak / field hilang -> abaikan
    }
}
