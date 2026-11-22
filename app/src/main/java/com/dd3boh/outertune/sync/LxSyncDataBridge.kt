package com.dd3boh.outertune.sync

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.source.lx.LxMusicInfo
import com.dd3boh.outertune.source.lx.lxJson
import com.dd3boh.outertune.source.lx.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * lx 同步与 Room 数据库之间的桥接。
 *
 * 映射规则（与 lx-server 协议对齐）：
 *  - loveList  <-> song.liked = 1
 *  - userList  <-> playlist 表（排除系统歌单）+ playlist_song_map
 *  - 单曲保真  <-> song.sourceData（完整 LxMusicInfo JSON）
 */
@Singleton
class LxSyncDataBridge @Inject constructor(
    private val database: MusicDatabase,
) {
    companion object {
        // 系统歌单不参与同步
        private val SYSTEM_PLAYLISTS = setOf(
            PlaylistEntity.LIKED_PLAYLIST_ID,
            PlaylistEntity.DOWNLOADED_PLAYLIST_ID,
        )
    }

    /** 从 Room 组装本地 ListData（对齐桌面版 getLocalListData） */
    suspend fun getLocalListData(): LxListData = withContext(Dispatchers.IO) {
        // 1. 喜欢列表
        val likedSongs = database.likedSongsByCreateDateAsc().first()
        val loveList = likedSongs.mapNotNull { it.song.toLxMusicInfo() }

        // 2. 自建歌单
        val allPlaylists = database.playlistInLibraryAsc().first()
        val userList = mutableListOf<LxUserList>()
        for (playlist in allPlaylists) {
            val p = playlist.playlist
            if (p.id in SYSTEM_PLAYLISTS || !p.isEditable) continue
            val songs = database.playlistSongs(p.id).first()
            val items = songs.mapNotNull { ps ->
                val song = database.song(ps.map.songId).first()?.song ?: return@mapNotNull null
                song.toLxMusicInfo()
            }
            userList.add(
                LxUserList(
                    id = p.id,
                    name = p.name,
                    list = items,
                ),
            )
        }

        LxListData(defaultList = emptyList(), loveList = loveList, userList = userList)
    }

    /** 把服务端 ListData 写回 Room（overwrite 语义） */
    suspend fun setLocalListData(data: LxListData): Unit = withContext(Dispatchers.IO) {
        // Room 的 transaction lambda 是非 suspend 的，Flow 读取必须先在外面完成
        val currentlyLiked = database.likedSongsByCreateDateAsc().first()
            .map { it.song.copy(liked = false, likedDate = null) }
        val existingPlaylistIds = data.userList.map { list ->
            list.id to database.playlist(list.id).first()
        }
        // insert() 用 IGNORE：歌曲已存在时 block 不会执行，liked 标记会丢失。
        // 预先读出已存在的歌曲，事务内对它们显式 update。
        val existingSongsById = data.loveList.associate { lx ->
            val metadata = lx.toMediaMetadata()
            metadata.id to database.song(metadata.id).first()?.song
        }

        database.transaction {
            // 1. loveList：先把现有 liked 清空，再标记新的
            currentlyLiked.forEach { database.update(it) }
            data.loveList.forEach { lx ->
                val metadata = lx.toMediaMetadata()
                // 新歌：insert 带 block 写入 liked 并补齐艺术家/专辑关联
                database.insert(metadata) { entity ->
                    entity.copy(liked = true, likedDate = LocalDateTime.now())
                }
                // 已存在的歌：上面 insert 被 IGNORE，block 不执行，需显式补一次 liked
                existingSongsById[metadata.id]?.let { existing ->
                    database.update(existing.copy(liked = true, likedDate = LocalDateTime.now()))
                }
            }

            // 2. userList：重建歌单歌曲映射
            for (remoteList in data.userList) {
                // 系统歌单不参与同步，避免覆盖「喜欢的音乐」「下载」
                if (remoteList.id in SYSTEM_PLAYLISTS) continue

                val existingPlaylist =
                    existingPlaylistIds.firstOrNull { it.first == remoteList.id }?.second?.playlist
                val playlistId = if (existingPlaylist == null) {
                    // 关键：Anhe 歌单列表只显示 bookmarkedAt != null 或 isLocal = 1 的歌单。
                    // 之前两者都没设，导致数据写入 Room 但界面永远看不到（表现为「已同步却无歌单」）。
                    // 用远端 id 作为主键，保证多次同步能稳定匹配同一歌单。
                    val newEntity = PlaylistEntity(
                        id = remoteList.id.ifBlank { PlaylistEntity.generatePlaylistId() },
                        name = remoteList.name,
                        isEditable = true,
                        isLocal = true,
                        bookmarkedAt = LocalDateTime.now(),
                    )
                    database.insert(newEntity)
                    newEntity.id
                } else {
                    // 已存在但不可见的歌单（老版本同步遗留）同样修正可见性
                    if (!existingPlaylist.isLocal && existingPlaylist.bookmarkedAt == null) {
                        database.update(
                            existingPlaylist.copy(
                                isLocal = true,
                                bookmarkedAt = LocalDateTime.now(),
                            ),
                        )
                    }
                    existingPlaylist.id
                }

                database.clearPlaylist(playlistId)
                remoteList.list.forEachIndexed { index, lx ->
                    val metadata = lx.toMediaMetadata()
                    database.insert(metadata) { it }
                    database.insert(
                        com.dd3boh.outertune.db.entities.PlaylistSongMap(
                            playlistId = playlistId,
                            songId = metadata.id,
                            position = index,
                        ),
                    )
                }
            }
        }
    }

    /** 统计本地歌曲总数 */
    suspend fun countSongs(): Int = withContext(Dispatchers.IO) {
        val data = getLocalListData()
        data.loveList.size + data.userList.sumOf { it.list.size }
    }

    /** SongEntity -> LxMusicInfo：优先从 sourceData 无损还原 */
    private fun SongEntity.toLxMusicInfo(): LxMusicInfo? {
        if (!source.isNullOrBlank()) {
            sourceData?.let { raw ->
                try {
                    return lxJson.decodeFromString<LxMusicInfo>(raw)
                } catch (_: Exception) {
                    /* 损坏则按字段重建 */
                }
            }
            // 有 source 但无 sourceData：按字段构造最小对象
            return LxMusicInfo(
                id = id,
                songmid = sourceId ?: id,
                name = title,
                singer = "",
                source = source!!,
                interval = null,
                meta = com.dd3boh.outertune.source.lx.LxMusicMeta(
                    songId = sourceId ?: id,
                    albumName = albumName ?: "",
                ),
            )
        }
        // 本地文件（无 source）不参与 lx 同步
        return null
    }
}
