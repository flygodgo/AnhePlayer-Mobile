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
import java.util.concurrent.ConcurrentHashMap

/**
 * message2call 线格式的 Kotlin 实现，对齐 AnhePlayer 桌面版 protocol.ts 的 Msg2Call。
 *
 * 线格式：
 *  - 调用：{"name": id, "path": ["a","b"], "data": [参数...]}
 *  - 响应：{"name": id, "error": null|string, "data": 结果}
 *
 * 关键点：参数与返回值全部用 JsonElement 原生传递，不做字符串化，
 * 否则服务端下发的 ListData 对象会被 `.trim('"')` 破坏，导致同步数据写入失败。
 */
internal class Msg2Call(
    private val funcs: Map<String, suspend (List<JsonElement>) -> JsonElement>,
    private val sendMessage: (String) -> Unit,
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private var seq = 0

    @Synchronized
    fun callRemote(path: List<String>, data: List<JsonElement>): CompletableDeferred<JsonElement> {
        val name = "${path.joinToString(".")}__${System.currentTimeMillis()}_${seq++}"
        val deferred = CompletableDeferred<JsonElement>()
        pending[name] = deferred
        val payload = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("path", buildJsonArray { path.forEach { add(JsonPrimitive(it)) } })
            put("data", buildJsonArray { data.forEach { add(it) } })
        }
        sendMessage(payload.toString())
        return deferred
    }

    /** 收到对端消息（JSON 字符串）时调用 */
    suspend fun onMessage(text: String) {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val name = (obj["name"] as? JsonPrimitive)?.content ?: return
        val path = obj["path"] as? JsonArray

        if (!path.isNullOrEmpty()) {
            // 对端调用本地方法：取 path 最后一段作为方法名
            val method = (path.lastOrNull() as? JsonPrimitive)?.content ?: return
            val dataArray = obj["data"] as? JsonArray ?: JsonArray(emptyList())

            // 对齐 onCallBeforeParams: [null, ...args]（socket 槽位）
            val args = mutableListOf<JsonElement>(JsonNull)
            dataArray.forEach { args.add(it) }

            val fn = funcs[method]
            if (fn == null) {
                sendMessage(
                    buildJsonObject {
                        put("name", JsonPrimitive(name))
                        put("error", JsonPrimitive("$method is not defined"))
                    }.toString(),
                )
                return
            }
            try {
                val result = fn(args)
                sendMessage(
                    buildJsonObject {
                        put("name", JsonPrimitive(name))
                        put("error", JsonNull)
                        put("data", result)
                    }.toString(),
                )
            } catch (e: Exception) {
                sendMessage(
                    buildJsonObject {
                        put("name", JsonPrimitive(name))
                        put("error", JsonPrimitive(e.message ?: e.toString()))
                    }.toString(),
                )
            }
        } else {
            // 本地调用的响应
            val deferred = pending.remove(name) ?: return
            val errorEl = obj["error"]
            val isError = errorEl != null && errorEl !is JsonNull &&
                (errorEl as? JsonPrimitive)?.content?.isNotBlank() == true
            if (!isError) {
                deferred.complete(obj["data"] ?: JsonNull)
            } else {
                deferred.completeExceptionally(
                    RuntimeException((errorEl as? JsonPrimitive)?.content ?: "rpc error"),
                )
            }
        }
    }

    fun destroy() {
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
}
