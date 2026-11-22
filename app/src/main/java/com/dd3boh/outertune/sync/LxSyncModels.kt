package com.dd3boh.outertune.sync

import com.dd3boh.outertune.source.lx.LxMusicInfo
import kotlinx.serialization.Serializable

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
    val id: String,
    val name: String,
    val source: String? = null,
    val sourceListId: String? = null,
    val locationUpdateTime: Long? = null,
    val list: List<LxMusicInfo> = emptyList(),
)

/** 授权握手成功后服务端返回的 clientKey 信息 */
@Serializable
data class LxClientKeyInfo(
    val clientId: String,
    val key: String,
    val serverName: String? = null,
)

/** 增量动作（与 lx 一致：{ action, data }） */
@Serializable
data class LxListAction(
    val action: String,
    val data: String? = null,
)
