package com.dd3boh.outertune.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * message2call 线格式的最小 Kotlin 实现，对齐 AnhePlayer 桌面版 protocol.ts 的 Msg2Call。
 *
 * 线格式（全部用 JsonElement 表达，规避 Any 序列化问题）：
 *  - 调用：{"name": id, "path": ["a","b"], "data": [参数...]}
 *  - 响应：{"name": id, "error": null|string, "data": 结果}
 *
 * 所有 RPC 方法的参数与返回值都先 JSON 化为字符串，协议层透传 string。
 */
internal class Msg2Call(
    private val funcs: Map<String, suspend (List<String>) -> String>,
    private val sendMessage: (String) -> Unit,
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private var seq = 0

    /** 远程调用：data 为已 JSON 字符串化的参数列表 */
    @Synchronized
    fun callRemote(path: List<String>, data: List<String>): CompletableDeferred<String> {
        val name = "${path.joinToString(".")}__${System.currentTimeMillis()}_${seq++}"
        val deferred = CompletableDeferred<String>()
        pending[name] = deferred
        val payload = buildJsonObject {
            put("name", name)
            put("path", buildJsonArray { path.forEach { add(JsonPrimitive(it)) } })
            put("data", buildJsonArray { data.forEach { add(parseOrString(it)) } })
        }
        sendMessage(payload.toString())
        return deferred
    }

    fun call(method: String, vararg args: String): CompletableDeferred<String> =
        callRemote(listOf(method), args.toList())

    /** 收到对端消息（JSON 字符串）时调用 */
    suspend fun onMessage(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val name = obj["name"]?.jsonPrimitive?.content ?: return
        val path = obj["path"]?.jsonArray

        if (!path.isNullOrEmpty()) {
            // 对端调用本地方法
            val method = path.last().jsonPrimitive.content
            val dataArray = obj["data"]?.jsonArray ?: JsonArray(emptyList())
            // 对齐 onCallBeforeParams: [null, ...args]（socket 槽位）
            val args = mutableListOf<String>(JsonNull.toString())
            dataArray.forEach { args.add(it.toString()) }

            val fn = funcs[method]
            if (fn == null) {
                sendMessage(
                    buildJsonObject {
                        put("name", name)
                        put("error", JsonPrimitive("$method is not defined"))
                    }.toString(),
                )
                return
            }
            try {
                val result = fn(args)
                sendMessage(
                    buildJsonObject {
                        put("name", name)
                        put("error", JsonNull)
                        put("data", parseOrString(result))
                    }.toString(),
                )
            } catch (e: Exception) {
                sendMessage(
                    buildJsonObject {
                        put("name", name)
                        put("error", JsonPrimitive(e.message ?: e.toString()))
                    }.toString(),
                )
            }
        } else {
            // 本地调用的响应
            val deferred = pending.remove(name) ?: return
            val errorEl = obj["error"]
            val isError = errorEl != null && errorEl !is JsonNull &&
                errorEl.jsonPrimitiveOrNull()?.content?.isNotBlank() == true
            if (!isError) {
                deferred.complete(obj["data"]?.toString() ?: JsonNull.toString())
            } else {
                deferred.completeExceptionally(
                    RuntimeException(errorEl!!.jsonPrimitiveOrNull()?.content ?: "rpc error"),
                )
            }
        }
    }

    /** 字符串若是合法 JSON 则还原为 JsonElement，否则当字符串 */
    private fun parseOrString(s: String): JsonElement =
        runCatching { json.parseToJsonElement(s) }.getOrDefault(JsonPrimitive(s))

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    fun destroy() {
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
}
