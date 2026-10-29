package com.futureyyy.tijing

import com.google.gson.JsonElement

data class TapPoint(val x: Float, val y: Float, val durationMs: Long = 100) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0f..100f && y in 0f..100f) { "点击位置必须在屏幕的 0% 到 100% 之间" }
        require(durationMs in 50L..500L) { "点击时长必须在 50 到 500 毫秒之间" }
    }
    fun box() = NormalizedBox((x - 0.05f).coerceIn(0f, 99.9f), (y - 0.05f).coerceIn(0f, 99.9f), 0.1f, 0.1f)
}

data class CustomClicks(
    val next: TapPoint = TapPoint(87.5f, 94f),
    val confirm: TapPoint = TapPoint(50f, 94f),
    val confirmFirst: Boolean = false,
) {
    companion object {
        fun parse(raw: JsonElement?): CustomClicks {
            if (raw == null || raw.isJsonNull) return CustomClicks()
            require(raw.isJsonObject) { "自定义点击配置无效" }
            val json = raw.asJsonObject
            fun point(name: String, fallback: TapPoint): TapPoint {
                val p = json.get(name) ?: return fallback
                require(p.isJsonObject) { "$name 点击位置必须是对象" }
                fun number(key: String, fallbackValue: Float): Float {
                    val v = p.asJsonObject.get(key) ?: return fallbackValue
                    require(v.isJsonPrimitive && v.asJsonPrimitive.isNumber) { "$name.$key 必须为数字" }
                    return v.asFloat
                }
                val ms = p.asJsonObject.get("durationMs")?.let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "点击时长必须为整数" }
                    try { it.asBigDecimal.longValueExact() }
                    catch (e: ArithmeticException) { throw IllegalArgumentException("点击时长必须为整数", e) }
                } ?: fallback.durationMs
                return TapPoint(number("x", fallback.x), number("y", fallback.y), ms)
            }
            return CustomClicks(point("next", TapPoint(87.5f, 94f)), point("confirm", TapPoint(50f, 94f)),
                json.get("confirmFirst")?.asBoolean ?: false)
        }
    }
}

sealed class NavigationCommand {
    data class Click(val point: TapPoint) : NavigationCommand()
    data class Swipe(val path: SwipePath) : NavigationCommand()
    data object Confirm : NavigationCommand()

    companion object {
        fun from(config: QuizRuntimeConfig): NavigationCommand = when (config.navigation) {
            "tap" -> Click(config.customClicks.next)
            "up", "left", "right" -> Swipe(config.swipeProfiles.getValue(FlowSwipe.valueOf(config.navigation.uppercase())))
            else -> Confirm
        }
    }
}
