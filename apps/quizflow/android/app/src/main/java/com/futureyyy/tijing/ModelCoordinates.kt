package com.futureyyy.tijing

import com.google.gson.JsonElement
import com.google.gson.JsonObject

class CoordinateResponseException(message: String) : IllegalArgumentException(message)

/** Convert only declared units/formats. Never guess a pixel/xyxy box and click it. */
class ModelCoordinates(
    private val space: String = "percent",
    private val format: String = "xywh",
    private val imageWidth: Int? = null,
    private val imageHeight: Int? = null,
) {
    fun box(value: JsonElement?, field: String): NormalizedBox? {
        if (value == null || value.isJsonNull) return null
        try {
            if (!value.isJsonArray || value.asJsonArray.size() != 4) fail(field, "需要四个坐标数值")
            val a = value.asJsonArray.map {
                if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) fail(field, "坐标必须是数字")
                it.asDouble.also { n -> if (!n.isFinite()) fail(field, "坐标不能为 NaN 或无穷大") }
            }
            // Some detectors use all zeros for 'not visible'. It is never a clickable region.
            if (a.all { it == 0.0 }) return null
            var x = a[0]; var y = a[1]
            var width: Double; var height: Double
            when (format.lowercase()) {
                "xywh" -> { width = a[2]; height = a[3] }
                "xyxy" -> { width = a[2] - x; height = a[3] - y }
                else -> fail(field, "未知坐标格式 $format，请使用 xywh")
            }
            val (scaleX, scaleY) = when (space.lowercase()) {
                "percent" -> 1.0 to 1.0
                "normalized" -> 100.0 to 100.0
                "pixels", "pixel" -> {
                    if (imageWidth == null || imageHeight == null || imageWidth <= 0 || imageHeight <= 0) {
                        fail(field, "像素坐标缺少真实截图尺寸")
                    }
                    100.0 / imageWidth!! to 100.0 / imageHeight!!
                }
                else -> fail(field, "未知坐标单位 $space，请使用 percent")
            }
            x *= scaleX; width *= scaleX; y *= scaleY; height *= scaleY
            if (width <= 0.0 || height <= 0.0 || x < -EPSILON || y < -EPSILON ||
                x >= 100.0 || y >= 100.0 || x + width > 100.0 + EPSILON || y + height > 100.0 + EPSILON) {
                fail(field, "坐标超出截图范围 $a（单位 $space，格式 $format），需要重新识别")
            }
            // Only tolerate tiny decimal rounding at the display edge, never large clipping.
            val right = (x + width).coerceAtMost(100.0)
            val bottom = (y + height).coerceAtMost(100.0)
            x = x.coerceAtLeast(0.0); y = y.coerceAtLeast(0.0)
            val xf = x.toFloat(); val yf = y.toFloat()
            return NormalizedBox(xf, yf, (right.toFloat() - xf).coerceAtMost(100f - xf),
                (bottom.toFloat() - yf).coerceAtMost(100f - yf))
        } catch (e: CoordinateResponseException) { throw e }
        catch (e: Exception) { fail(field, "坐标格式无效，请重新识别") }
    }

    private fun fail(field: String, message: String): Nothing = throw CoordinateResponseException("$field：$message")

    companion object {
        private const val EPSILON = 0.05
        fun fromResponse(json: JsonObject, imageWidth: Int? = null, imageHeight: Int? = null) = ModelCoordinates(
            json.get("coordinate_space")?.takeUnless { it.isJsonNull }?.asString ?: "percent",
            json.get("box_format")?.takeUnless { it.isJsonNull }?.asString ?: "xywh", imageWidth, imageHeight,
        )
    }
}
