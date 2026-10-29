package com.futureyyy.tijing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun interface ModelRequestHandle {
    fun cancel()
}

object ModelClient {
    private val executor = Executors.newCachedThreadPool()

    fun request(context: Context, payloadText: String, requestId: String, connectionCheck: Boolean) {
        requestAsync(payloadText, connectionCheck) { result ->
            val activity = context as? MainActivity ?: return@requestAsync
            result.fold(
                onSuccess = { content -> activity.deliverModelResponse(requestId, content, null) },
                onFailure = { error ->
                    activity.deliverModelResponse(requestId, "", error.message ?: "模型请求失败")
                },
            )
        }
    }

    fun requestAsync(
        payloadText: String,
        connectionCheck: Boolean = false,
        callback: (Result<String>) -> Unit,
    ): ModelRequestHandle {
        val control = RequestControl(callback)
        val future = executor.submit {
            var key = ""
            val result = try {
                control.throwIfCancelled()
                val payload = JSONObject(payloadText)
                val endpoint = normalizeEndpoint(payload.optString("endpoint"))
                val model = payload.optString("model")
                key = payload.optString("apiKey")
                if (key.isBlank()) throw IllegalArgumentException("请在 App 设置中填写 API Key")
                val timeout = payload.optInt("timeoutMs", 30000).coerceIn(5000, 120000)
                val body = buildRequest(payload, connectionCheck, model)
                val retries = payload.optInt("retryCount", 0).coerceIn(0, 5)
                var content = ""
                var lastError: Exception? = null
                for (attempt in 0..retries) {
                    control.throwIfCancelled()
                    try {
                        content = post(endpoint, key, body, timeout, control)
                        lastError = null
                        break
                    } catch (error: Exception) {
                        control.throwIfCancelled()
                        lastError = error
                        if (attempt < retries) {
                            try {
                                Thread.sleep(450L * (attempt + 1))
                            } catch (interrupted: InterruptedException) {
                                Thread.currentThread().interrupt()
                                control.throwIfCancelled()
                                throw interrupted
                            }
                        }
                    }
                }
                lastError?.let { throw it }
                Result.success(content)
            } catch (error: Exception) {
                Result.failure(sanitizeError(error, key))
            }
            control.complete(result)
        }
        control.attachFuture(future)
        return control
    }

    private fun sanitizeError(error: Exception, key: String): Exception {
        val rawMessage = error.message ?: "模型请求失败"
        val message = if (key.isBlank()) rawMessage else rawMessage.replace(key, "***")
        return IllegalStateException(message)
    }

    private fun buildRequest(payload: JSONObject, connectionCheck: Boolean, model: String): JSONObject {
        val messages = JSONArray()
        if (connectionCheck) {
            messages.put(JSONObject().put("role", "system").put("content", "只回复 READY。"))
            messages.put(JSONObject().put("role", "user").put("content", "连接测试。"))
        } else {
            val prompt = payload.optString("prompt")
                .replace("{QUESTION_NO}", payload.optInt("questionNo", 0).toString())
                .replace("{QUESTION_IMAGE}", "题目截图已作为 image_url 附件提供")
            messages.put(JSONObject().put("role", "system").put("content", prompt))
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", "这是第 ${payload.optInt("questionNo", 0)} 题，请按系统提示词返回 JSON。"))
            val images = payload.optJSONArray("images") ?: JSONArray().put(payload.optString("image"))
            for (i in 0 until images.length()) {
                content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", images.getString(i))))
            }
            messages.put(JSONObject().put("role", "user").put("content", content))
        }
        val request = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0)
            .put("max_tokens", if (connectionCheck) 8 else payload.optInt("maxTokens", 1200).coerceIn(1200, 8000))
        if (!connectionCheck) request.put("response_format", JSONObject().put("type", "json_object"))
        return request
    }

    private fun post(
        endpoint: String,
        key: String,
        body: JSONObject,
        timeout: Int,
        control: RequestControl,
    ): String {
        var response = execute(endpoint, key, body, timeout, control)
        if (response.first in 400..499 && response.first != 401 && body.has("response_format")) {
            control.throwIfCancelled()
            response = execute(endpoint, key, body.apply { remove("response_format") }, timeout, control)
        }
        if (response.first !in 200..299) throw IllegalStateException("模型返回 ${response.first}: ${response.second.take(240)}")
        val json = JSONObject(response.second)
        val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        return when (content) {
            is String -> content
            is JSONArray -> buildString { for (i in 0 until content.length()) append(content.optJSONObject(i)?.optString("text") ?: "") }
            else -> if (body.has("response_format")) "" else "READY"
        }
    }

    private fun execute(
        endpoint: String,
        key: String,
        body: JSONObject,
        timeout: Int,
        control: RequestControl,
    ): Pair<Int, String> {
        control.throwIfCancelled()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeout
            readTimeout = timeout
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        control.attachConnection(connection)
        try {
            control.throwIfCancelled()
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            control.throwIfCancelled()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            control.throwIfCancelled()
            return status to response
        } finally {
            control.clearConnection(connection)
            connection.disconnect()
        }
    }

    private fun normalizeEndpoint(raw: String): String {
        val base = raw.trim().trimEnd('/').ifBlank { "https://api.openai.com/v1" }
        return if (base.endsWith("/chat/completions", ignoreCase = true)) base else "$base/chat/completions"
    }

    private class RequestControl(
        private val callback: (Result<String>) -> Unit,
    ) : ModelRequestHandle {
        private val cancelled = AtomicBoolean(false)
        private val delivered = AtomicBoolean(false)
        private val connection = AtomicReference<HttpURLConnection?>(null)

        @Volatile
        private var future: Future<*>? = null

        fun attachFuture(value: Future<*>) {
            future = value
            if (cancelled.get()) value.cancel(true)
        }

        fun attachConnection(value: HttpURLConnection) {
            connection.set(value)
            if (cancelled.get()) {
                connection.compareAndSet(value, null)
                value.disconnect()
                throw CancellationException("模型请求已取消")
            }
        }

        fun clearConnection(value: HttpURLConnection) {
            connection.compareAndSet(value, null)
        }

        fun throwIfCancelled() {
            if (cancelled.get()) throw CancellationException("模型请求已取消")
        }

        fun complete(result: Result<String>) {
            if (delivered.compareAndSet(false, true)) runCatching { callback(result) }
        }

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            connection.getAndSet(null)?.disconnect()
            future?.cancel(true)
            complete(Result.failure(CancellationException("模型请求已取消")))
        }
    }
}
