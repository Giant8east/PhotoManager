/** 局域网白名单的数据入口：实现协议层的 WhitelistStore，并给设置页提供设备列表。 */
package com.giant8east.photomanager.data.lan

import com.giant8east.photomanager.data.database.AllowedDeviceEntity
import com.giant8east.photomanager.data.database.PhotoDatabase
import com.giant8east.photomanager.lan.server.WhitelistStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class AllowedDeviceData @Inject constructor(
    private val database: PhotoDatabase
) : WhitelistStore {
    /** 供主机"远程连接"页展示已授权设备。 */
    fun observeAllowedDevices(): Flow<List<AllowedDeviceEntity>> =
        database.allowedDeviceDao().observeAllowedDevices()

    /** 协议层只关心"是否记住过"，autoApprove 目前恒为 true。 */
    override suspend fun contains(deviceId: String): Boolean =
        database.allowedDeviceDao().queryAllowedDevice(deviceId)?.autoApprove == true

    override suspend fun add(deviceId: String, name: String) {
        val dao = database.allowedDeviceDao()
        val now = System.currentTimeMillis()
        val existing = dao.queryAllowedDevice(deviceId)
        dao.insertAllowedDevice(
            AllowedDeviceEntity(
                deviceId = deviceId,
                name = name,
                pairedAt = existing?.pairedAt ?: now,
                lastSeenAt = now,
                autoApprove = true
            )
        )
    }

    override suspend fun remove(deviceId: String) {
        database.allowedDeviceDao().deleteAllowedDevice(deviceId)
    }
}
