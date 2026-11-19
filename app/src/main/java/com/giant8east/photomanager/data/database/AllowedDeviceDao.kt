/** 局域网白名单的数据库访问对象。 */
package com.giant8east.photomanager.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AllowedDeviceDao {
    /** 主机侧"已授权设备"列表，最近访问的排在前面。 */
    @Query("SELECT * FROM allowed_devices ORDER BY lastSeenAt DESC")
    fun observeAllowedDevices(): Flow<List<AllowedDeviceEntity>>

    @Query("SELECT * FROM allowed_devices WHERE deviceId = :deviceId")
    suspend fun queryAllowedDevice(deviceId: String): AllowedDeviceEntity?

    /** 重复记住同一设备时覆盖名称与时间，主键不变。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllowedDevice(device: AllowedDeviceEntity)

    @Query("DELETE FROM allowed_devices WHERE deviceId = :deviceId")
    suspend fun deleteAllowedDevice(deviceId: String)
}
