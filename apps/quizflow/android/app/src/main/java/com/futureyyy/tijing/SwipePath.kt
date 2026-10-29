package com.futureyyy.tijing

import com.google.gson.JsonElement

/** Coordinates are percentages of the full display, including system bars. */
data class SwipePath(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long = 450,
) {
    init {
        require(listOf(startX, startY, endX, endY).all { it.isFinite() && it in 0f..100f }) {
            "滑动坐标必须在屏幕的 0% 到 100% 之间"
        }
        require(durationMs in 50L..5000L) { "滑动时长必须在 50 到 5000 毫秒之间" }
        require(kotlin.math.hypot(endX - startX, endY - startY) >= 1f) { "滑动起点与终点距离至少为屏幕的 1%" }
    }

    fun requireDirection(direction: FlowSwipe): SwipePath = apply {
        require(when (direction) {
            FlowSwipe.UP -> endY < startY
            FlowSwipe.DOWN -> endY > startY
            FlowSwipe.LEFT -> endX < startX
            FlowSwipe.RIGHT -> endX > startX
        }) { "滑动起终点与所选方向不一致" }
    }

    fun pixels(width: Int, height: Int): List<Float> {
        require(width > 0 && height > 0)
        return listOf(startX / 100f * (width - 1), startY / 100f * (height - 1),
            endX / 100f * (width - 1), endY / 100f * (height - 1))
    }

    companion object {
        fun defaultFor(direction: FlowSwipe): SwipePath = when (direction) {
            FlowSwipe.UP -> SwipePath(50f, 80f, 50f, 25f)
            FlowSwipe.LEFT -> SwipePath(85f, 50f, 15f, 50f)
            FlowSwipe.RIGHT -> SwipePath(15f, 50f, 85f, 50f)
            FlowSwipe.DOWN -> SwipePath(50f, 25f, 50f, 80f)
        }

        fun defaults(): Map<FlowSwipe, SwipePath> = listOf(FlowSwipe.UP, FlowSwipe.LEFT, FlowSwipe.RIGHT)
            .associateWith(::defaultFor)

        fun parseProfiles(value: JsonElement?): Map<FlowSwipe, SwipePath> {
            if (value == null || value.isJsonNull) return defaults()
            require(value.isJsonObject) { "滑动范围配置必须是对象" }
            return defaults().mapValues { (direction, default) ->
                val raw = value.asJsonObject.get(direction.name.lowercase())
                if (raw == null || raw.isJsonNull) default else {
                    require(raw.isJsonObject) { "滑动范围配置无效" }
                    val json = raw.asJsonObject
                    fun coordinate(name: String, fallback: Float): Float {
                        val v = json.get(name) ?: return fallback
                        require(v.isJsonPrimitive && v.asJsonPrimitive.isNumber) { "$name 必须是数字" }
                        return v.asFloat
                    }
                    val duration = json.get("durationMs")?.let {
                        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "滑动时长必须是整数" }
                        try { it.asBigDecimal.longValueExact() }
                        catch (error: ArithmeticException) { throw IllegalArgumentException("滑动时长必须是整数", error) }
                    } ?: default.durationMs
                    SwipePath(coordinate("startX", default.startX), coordinate("startY", default.startY),
                        coordinate("endX", default.endX), coordinate("endY", default.endY), duration).requireDirection(direction)
                }
            }
        }
    }
}
