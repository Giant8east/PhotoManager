/** 局域网白名单的数据入口：实现协议层的 WhitelistStore，并给设置页提供设备列表。 */
package com.giant8east.photomanager.data.lan

import com.giant8east.photomanager.data.database.AllowedDeviceEntity
import com.giant8east.photomanager.data.database.PhotoDatabase
import com.giant8east.photomanager.lan.protocol.LanTrustedDevice
import com.giant8east.photomanager.lan.server.WhitelistStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AllowedDeviceData @Inject constructor(
    private val database: PhotoDatabase
) : WhitelistStore {
    /**
     * DAO 的 Flow 原样透传，本类里只有 [observeTrusted] 用它（做类型转换）。
     * 排序在 DAO 的 SQL 里做（最近访问时间倒序），这里不重排。
     */
    fun observeAllowedDevices(): Flow<List<AllowedDeviceEntity>> =
        database.allowedDeviceDao().observeAllowedDevices()

    /**
     * 配对时判断走"免确认直接发 token"还是"等主机点头"。
     *
     * 看的是 `autoApprove` 而不是"行是否存在"：白名单的语义是"下次免确认"，
     * 将来若要支持"记住设备但仍每次确认"，只要把这一列置 false，本方法不用改。
     * 目前所有批准路径都写 true。
     */
    override suspend fun contains(deviceId: String): Boolean =
        database.allowedDeviceDao().queryAllowedDevice(deviceId)?.autoApprove == true

    /**
     * 向上传递白名单数据变更
     */
    override fun observeTrusted(): Flow<List<LanTrustedDevice>> =
        observeAllowedDevices().map { devices ->
            devices.map { device ->
                LanTrustedDevice(
                    deviceId = device.deviceId,
                    name = device.name,
                    lastSeenAt = device.lastSeenAt
                )
            }
        }

    /**
     * 记住一台设备（批准配对时调用）。
     *
     * 先查再插是为了留住 `pairedAt`：DAO 用的是 REPLACE，同一台设备第二次批准会整行覆盖，
     * 若直接把 `pairedAt` 写成 now，"什么时候第一次认识的"就永远查不到了。
     * `lastSeenAt` 则应该刷新——它就是最近一次来访问的时间。
     */
    override suspend fun add(deviceId: String, name: String) {
        val dao = database.allowedDeviceDao()
        val now = System.currentTimeMillis()
        val existing = dao.queryAllowedDevice(deviceId)
        dao.insertAllowedDevice(
            AllowedDeviceEntity(
                deviceId = deviceId,
                name = name,
                // 等价于 if (existing != null) existing.pairedAt else now
                pairedAt = existing?.pairedAt ?: now,
                lastSeenAt = now,
                autoApprove = true
            )
        )
    }

    /**
     * 撤销：只忘记这台设备，不负责断开它。
     */
    override suspend fun remove(deviceId: String) {
        database.allowedDeviceDao().deleteAllowedDevice(deviceId)
    }
}
