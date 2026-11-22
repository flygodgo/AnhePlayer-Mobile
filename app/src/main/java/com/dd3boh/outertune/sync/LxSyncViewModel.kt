package com.dd3boh.outertune.sync

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 洛雪同步设置页的 ViewModel，代理 LxSyncManager 的状态与操作。 */
@HiltViewModel
class LxSyncViewModel @Inject constructor(
    private val manager: LxSyncManager,
) : ViewModel() {

    val status = manager.status

    fun isEnabled(): Boolean = manager.isEnabled()
    fun getServerUrl(): String = manager.getServerUrl()
    fun getAuthCode(): String = manager.getAuthCode()
    fun getMode(): LxSyncMode = manager.getMode()

    fun saveConfig(enabled: Boolean, url: String, authCode: String, mode: LxSyncMode) {
        manager.saveConfig(enabled, url, authCode, mode)
    }

    fun connect() {
        val url = manager.getServerUrl()
        if (url.isBlank()) return
        manager.connect(url, manager.getAuthCode().ifBlank { null }, manager.getMode())
    }

    fun syncNow() = manager.syncNow()
    fun disconnect() = manager.disconnect()
}
