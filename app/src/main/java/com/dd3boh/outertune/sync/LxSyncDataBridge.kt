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
                val song = database.song(ps.songId).first()?.song ?: return@mapNotNull null
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

    /** 把服务端 ListData 写回 Room（overwrite 语义，事务内完成） */
    suspend fun setLocalListData(data: LxListData): Unit = withContext(Dispatchers.IO) {
        database.transaction {
            // 1. loveList：先把现有 liked 清空，再标记新的
            val currentlyLiked = database.likedSongsByCreateDateAsc().first()
                .map { it.song.copy(liked = false, likedDate = null) }
            currentlyLiked.forEach { database.update(it) }

            data.loveList.forEach { lx ->
                upsertLxSong(lx) { entity ->
                    entity.copy(
                        liked = true,
                        likedDate = entity.likedDate ?: LocalDateTime.now(),
                    )
                }
            }

            // 2. userList：重建歌单歌曲映射
            for (remoteList in data.userList) {
                val existing = database.playlist(remoteList.id).first()
                val playlistId = if (existing == null) {
                    val newId = PlaylistEntity.generatePlaylistId()
                    database.insert(
                        PlaylistEntity(
                            id = newId,
                            name = remoteList.name,
                            isEditable = true,
                            isLocal = false,
                        ),
                    )
                    newId
                } else {
                    existing.playlist.id
                }

                database.clearPlaylist(playlistId)
                remoteList.list.forEachIndexed { index, lx ->
                    val entity = upsertLxSong(lx) { it }
                    if (entity != null) {
                        database.insert(
                            com.dd3boh.outertune.db.entities.PlaylistSongMap(
                                playlistId = playlistId,
                                songId = entity.id,
                                position = index,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** 单曲入库（含歌手/专辑关联），返回入库后的实体 */
    private suspend fun upsertLxSong(
        lx: LxMusicInfo,
        block: (SongEntity) -> SongEntity,
    ): SongEntity? {
        val metadata = lx.toMediaMetadata()
        database.insert(metadata, block)
        return database.song(metadata.id).first()
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
                } catch {
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
