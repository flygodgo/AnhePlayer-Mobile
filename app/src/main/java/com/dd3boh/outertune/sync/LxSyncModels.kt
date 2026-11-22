package com.dd3boh.outertune.sync

import com.dd3boh.outertune.source.lx.LxMusicInfo
import com.dd3boh.outertune.source.lx.LooseStringSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * lx 同步载荷，与 lx-music-desktop 的 LX.Sync.List.ListData 字段一一对应。
 * 字段顺序固定为 defaultList / loveList / userList，保证 MD5 与桌面版一致。
 */
@Serializable
data class LxListData(
    val defaultList: List<LxMusicInfo> = emptyList(),
    val loveList: List<LxMusicInfo> = emptyList(),
    val userList: List<LxUserList> = emptyList(),
)

@Serializable
data class LxUserList(
    val id: String = "",
    val name: String = "",
    val source: String? = null,
    val sourceListId: String? = null,
    @Serializable(with = LooseStringSerializer::class)
    val locationUpdateTime: String? = null,
    val list: List<LxMusicInfo> = emptyList(),
)

/** 授权握手成功后服务端返回的 clientKey 信息 */
@Serializable
data class LxClientKeyInfo(
    val clientId: String = "",
    val key: String = "",
    val serverName: String? = null,
)

/**
 * 增量动作。data 在 lx 中是对象（list_data_overwrite 时是 ListData，
 * dislike_data_overwrite 时是 string），所以必须用 JsonElement 承接，
 * 不能用 String —— 否则 decodeFromString 会抛 "Expected string literal" 异常。
 */
@Serializable
data class LxListAction(
    val action: String = "",
    val data: JsonElement? = null,
)
