package com.dd3boh.outertune.sync

import android.content.Context
import android.content.SharedPreferences
import com.dd3boh.outertune.source.lx.lxJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 同步状态（供 UI 观察） */
data class LxSyncStatus(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val message: String = "",
    val serverName: String? = null,
    val serverId: String? = null,
    val lastSyncAt: Long? = null,
    val syncedCount: Int = 0,
)

/** 同步模式（对齐 lx 的 6 种 + cancel） */
enum class LxSyncMode(val wire: String) {
    MERGE_LOCAL_REMOTE("merge_local_remote"),
    MERGE_REMOTE_LOCAL("merge_remote_local"),
    OVERWRITE_LOCAL_REMOTE("overwrite_local_remote"),
    OVERWRITE_REMOTE_LOCAL("overwrite_remote_local"),
    OVERWRITE_LOCAL_REMOTE_FULL("overwrite_local_remote_full"),
    OVERWRITE_REMOTE_LOCAL_FULL("overwrite_remote_local_full"),
    CANCEL("cancel");

    companion object {
        fun from(wire: String?): LxSyncMode =
            entries.find { it.wire == wire } ?: MERGE_LOCAL_REMOTE
    }
}

data class LxSyncResult(val success: Boolean, val message: String = "", val songsCount: Int = 0)

/**
 * lx-server v4 同步管理器（Android 版）。
 * - HTTP 握手：/hello -> /id -> /ah
 * - WebSocket：/socket?i=clientId&t=aes(msgConnect,key)
 * - message2call RPC + gzip 载荷
 */
@Singleton
class LxSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataBridge: LxSyncDataBridge,
) {
    companion object {
        private const val PREFS = "lx_sync_prefs"
        private const val KEY_AUTH_KEYS = "auth_keys"
        private const val DEVICE_NAME = "AnhePlayer"

        private const val HELLO_MSG = "Hello~::^-^::~v4~"
        private const val ID_PREFIX = "OjppZDo6"
        private const val AUTH_MSG = "lx-music auth::"
        private const val MSG_CONNECT = "lx-music connect"
        private const val AUTH_FAILED = "Auth failed"
        private const val BLOCKED_IP = "Blocked IP"

        // 与 lx-music-desktop featureVersion 一致
        private const val FEATURE_VERSION_LIST = 1
        private const val FEATURE_VERSION_DISLIKE = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val socketClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _status = MutableStateFlow(LxSyncStatus())
    val status: StateFlow<LxSyncStatus> = _status

    private var socket: WebSocket? = null
    private var rpc: Msg2Call? = null
    private var serverId: String? = null
    private var connectTimeout: java.util.Timer? = null
    private var currentMode: LxSyncMode = LxSyncMode.MERGE_LOCAL_REMOTE
    private val moduleReady = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ─── 配置 ───

    fun getServerUrl(): String = prefs.getString("server_url", "") ?: ""
    fun getAuthCode(): String = prefs.getString("auth_code", "") ?: ""
    fun isEnabled(): Boolean = prefs.getBoolean("enabled", false)
    fun getMode(): LxSyncMode = LxSyncMode.from(prefs.getString("mode", null))

    fun saveConfig(enabled: Boolean, url: String, authCode: String, mode: LxSyncMode) {
        prefs.edit()
            .putBoolean("enabled", enabled)
            .putString("server_url", url.trim().trimEnd('/'))
            .putString("auth_code", authCode.trim())
            .putString("mode", mode.wire)
            .apply()
        currentMode = mode
    }

    private fun loadAuthKeys(): MutableMap<String, LxClientKeyInfo> {
        val raw = prefs.getString(KEY_AUTH_KEYS, null) ?: return mutableMapOf()
        return try {
            lxJson.decodeFromString<MutableMap<String, LxClientKeyInfo>>(raw)
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveAuthKey(id: String, info: LxClientKeyInfo) {
        val map = loadAuthKeys()
        map[id] = info
        prefs.edit().putString(KEY_AUTH_KEYS, lxJson.encodeToString(map)).apply()
    }

    // ─── 握手 ───

    private data class UrlInfo(val httpBase: String, val wsProtocol: String)

    private fun parseUrl(host: String): UrlInfo {
        val trimmed = host.trim().trimEnd('/')
        val httpBase = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        val wsProtocol = if (httpBase.startsWith("https://")) "wss" else "ws"
        return UrlInfo(httpBase, wsProtocol)
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): Pair<String, Int> =
        runCatching {
            val req = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            http.newCall(req).execute().use { resp: Response ->
                resp.body?.string().orEmpty() to resp.code
            }
        }.getOrDefault("" to 0)

    private fun hello(u: UrlInfo): Boolean =
        httpGet("${u.httpBase}/hello").first == HELLO_MSG

    private fun getServerId(u: UrlInfo): String {
        val text = httpGet("${u.httpBase}/id").first
        return if (text.startsWith(ID_PREFIX)) text.removePrefix(ID_PREFIX) else ""
    }

    private fun codeAuth(u: UrlInfo, sid: String, code: String): LxClientKeyInfo {
        val key = LxSyncProtocol.deriveAuthKey(code)
        val rsa = LxSyncProtocol.generateRsaKeyPair()
        val pubB64 = LxSyncProtocol.exportSpkiBase64(rsa)
        val identity = "$AUTH_MSG\n$pubB64\n$DEVICE_NAME\nlx_music_desktop"
        val msg = LxSyncProtocol.aesEncrypt(identity, key)
        val (text, status) = httpGet("${u.httpBase}/ah", mapOf("m" to msg))
        if (text == BLOCKED_IP || text == AUTH_FAILED || status != 200) {
            throw RuntimeException(text.ifBlank { AUTH_FAILED })
        }
        val plain = LxSyncProtocol.rsaOaepDecrypt(text, rsa)
        val info = lxJson.decodeFromString<LxClientKeyInfo>(plain)
        saveAuthKey(sid, info)
        return info
    }

    private fun keyAuth(u: UrlInfo, info: LxClientKeyInfo) {
        val msg = LxSyncProtocol.aesEncrypt(AUTH_MSG + DEVICE_NAME, info.key)
        val (text, status) = httpGet(
            "${u.httpBase}/ah",
            mapOf("i" to info.clientId, "m" to msg),
        )
        if (status != 200) throw RuntimeException(AUTH_FAILED)
        val plain = LxSyncProtocol.aesDecrypt(text, info.key)
        if (plain != HELLO_MSG) throw RuntimeException(AUTH_FAILED)
    }

    // ─── RPC handlers ───

    /**
     * 把 Msg2Call 传进来的参数（第 0 位是 socket 槽位 null）解成 JSON 字符串。
     * 关键：服务端下发的参数可能是对象/字符串/number，统一用 JsonElement 承接后
     * 再 toString，避免 `.trim('"')` 这类粗暴处理破坏对象结构。
     */
    private fun arg(el: JsonElement?): String? = when (el) {
        null, JsonNull -> null
        is JsonPrimitive -> el.content
        else -> el.toString()
    }

    private fun buildFuncs(): Map<String, suspend (List<JsonElement>) -> JsonElement> = mapOf(
        "getEnabledFeatures" to { _ ->
            // 对齐 lx：仅当版本一致才启用对应模块（我们用固定版本，向下兼容任意 serverType）
            lxJson.parseToJsonElement(
                """{"list":{"skipSnapshot":false},"dislike":{"skipSnapshot":false}}""",
            )
        },
        "finished" to {
            val count = dataBridge.countSongs()
            updateStatus(
                connected = true,
                connecting = false,
                message = "同步成功",
                lastSyncAt = System.currentTimeMillis(),
                syncedCount = count,
            )
            JsonNull
        },
        "list_sync_get_md5" to {
            JsonPrimitive(LxSyncProtocol.md5(lxJson.encodeToString(dataBridge.getLocalListData())))
        },
        "list_sync_get_sync_mode" to { JsonPrimitive(currentMode.wire) },
        "list_sync_get_list_data" to { lxJson.encodeToJsonElement(dataBridge.getLocalListData()) },
        "list_sync_set_list_data" to { args ->
            val dataEl = args.getOrNull(1)
            if (dataEl != null && dataEl !is JsonNull) {
                try {
                    val data = lxJson.decodeFromJsonElement<LxListData>(dataEl)
                    dataBridge.setLocalListData(data)
                    val count = dataBridge.countSongs()
                    updateStatus(message = "已接收云端歌单", syncedCount = count)
                } catch (e: Exception) {
                    updateStatus(message = "写入歌单失败: ${e.message}")
                    throw e
                }
            }
            JsonNull
        },
        "list_sync_finished" to {
            moduleReady["list"] = true
            JsonNull
        },
        "onListSyncAction" to { args ->
            // args[0] = socket 槽位(null)，args[1] = action 对象
            val raw = args.getOrNull(1)
            if (moduleReady["list"] == true && raw != null && raw !is JsonNull) {
                try {
                    val action = lxJson.decodeFromJsonElement<LxListAction>(raw)
                    if (action.action == "list_data_overwrite" && action.data != null) {
                        val data = lxJson.decodeFromJsonElement<LxListData>(action.data!!)
                        dataBridge.setLocalListData(data)
                        updateStatus(message = "已接收云端歌单", syncedCount = dataBridge.countSongs())
                    }
                } catch (e: Exception) {
                    updateStatus(message = "处理歌单动作失败: ${e.message}")
                    throw e
                }
            }
            JsonNull
        },
        // dislike：Android 无对应 UI，仅协议层收发（以字符串规则保存）
        "dislike_sync_get_md5" to { JsonPrimitive(LxSyncProtocol.md5(getDislikeRules().trim())) },
        "dislike_sync_get_sync_mode" to { JsonPrimitive("merge_local_remote") },
        "dislike_sync_get_list_data" to { JsonPrimitive(getDislikeRules()) },
        "dislike_sync_set_list_data" to { args ->
            setDislikeRules(arg(args.getOrNull(1)) ?: "")
            JsonNull
        },
        "dislike_sync_finished" to {
            moduleReady["dislike"] = true
            JsonNull
        },
        "onDislikeSyncAction" to { args ->
            val raw = args.getOrNull(1)
            if (raw != null && raw !is JsonNull) {
                runCatching {
                    val action = lxJson.decodeFromJsonElement<LxListAction>(raw)
                    if (action.action == "dislike_data_overwrite" && action.data != null) {
                        setDislikeRules(arg(action.data) ?: "")
                    }
                }
            }
            JsonNull
        },
    )

    private fun getDislikeRules(): String = prefs.getString("dislike_rules", "") ?: ""
    private fun setDislikeRules(rules: String) {
        prefs.edit().putString("dislike_rules", rules).apply()
    }

    // ─── WebSocket ───

    private fun openSocket(u: UrlInfo, keyInfo: LxClientKeyInfo) {
        val token = LxSyncProtocol.aesEncrypt(MSG_CONNECT, keyInfo.key)
        val host = u.httpBase.removePrefix("https://").removePrefix("http://")
        val wsUrl = "${u.wsProtocol}://$host/socket?i=${encode(keyInfo.clientId)}&t=${encode(token)}"

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connectTimeout?.cancel()
                updateStatus(connecting = false, message = "已连接，等待同步...")
                rpc = Msg2Call(
                    funcs = buildFuncs(),
                    sendMessage = { json -> webSocket.send(LxSyncProtocol.encodeData(json)) },
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "ping") return
                scope.launch {
                    runCatching {
                        val plain = LxSyncProtocol.decodeData(text)
                        rpc?.onMessage(plain)
                    }.onFailure { e ->
                        updateStatus(message = "消息处理失败: ${e.message}")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectTimeout?.cancel()
                updateStatus(connected = false, connecting = false, message = "连接失败: ${t.message}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updateStatus(connected = false, message = if (code == 1000) "" else "连接已断开")
                cleanup()
            }
        }

        val request = Request.Builder().url(wsUrl).build()
        socket = socketClient.newWebSocket(request, listener)

        connectTimeout = java.util.Timer().also { timer ->
            timer.schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (_status.value.connecting) {
                        updateStatus(connected = false, connecting = false, message = "连接超时")
                        disconnect()
                    }
                }
            }, 30_000)
        }
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    // ─── 公共 API ───

    fun connect(serverUrl: String, authCode: String? = null, mode: LxSyncMode = currentMode) {
        if (_status.value.connecting) return
        currentMode = mode
        updateStatus(connected = false, connecting = true, message = "连接中...")
        scope.launch {
            try {
                val u = parseUrl(serverUrl)
                if (!hello(u)) throw RuntimeException("无法连接服务器")
                val sid = getServerId(u)
                if (sid.isBlank()) throw RuntimeException("获取服务器 ID 失败")
                serverId = sid

                val keyInfo: LxClientKeyInfo
                if (!authCode.isNullOrBlank()) {
                    keyInfo = codeAuth(u, sid, authCode)
                } else {
                    val stored = loadAuthKeys()[sid]
                        ?: throw RuntimeException("缺少授权码")
                    keyAuth(u, stored)
                    keyInfo = stored
                }
                updateStatus(serverId = sid, serverName = keyInfo.serverName)
                withContext(Dispatchers.Main) { openSocket(u, keyInfo) }
            } catch (e: Exception) {
                updateStatus(connected = false, connecting = false, message = e.message ?: "连接失败")
            }
        }
    }

    fun syncNow() {
        val url = getServerUrl()
        if (url.isBlank()) {
            updateStatus(message = "请先填写服务器地址")
            return
        }
        disconnect()
        connect(url, getAuthCode().ifBlank { null }.takeIf { isEnabled() }, getMode())
    }

    fun autoConnect() {
        if (!isEnabled()) return
        val url = getServerUrl()
        if (url.isBlank()) return
        connect(url, getAuthCode().ifBlank { null }, getMode())
    }

    fun disconnect() {
        connectTimeout?.cancel()
        connectTimeout = null
        socket?.close(1000, null)
        socket = null
        cleanup()
        updateStatus(connected = false, connecting = false, serverId = null, serverName = null, message = "")
    }

    private fun cleanup() {
        rpc?.destroy()
        rpc = null
        moduleReady.clear()
    }

    private fun updateStatus(
        connected: Boolean = _status.value.connected,
        connecting: Boolean = _status.value.connecting,
        message: String = _status.value.message,
        serverId: String? = _status.value.serverId,
        serverName: String? = _status.value.serverName,
        lastSyncAt: Long? = _status.value.lastSyncAt,
        syncedCount: Int = _status.value.syncedCount,
    ) {
        _status.value = LxSyncStatus(connected, connecting, message, serverName, serverId, lastSyncAt, syncedCount)
    }

    suspend fun localSongCount(): Int = dataBridge.countSongs()
}
