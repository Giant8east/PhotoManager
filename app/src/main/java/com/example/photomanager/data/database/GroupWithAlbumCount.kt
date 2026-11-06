/** 分组及其相册数量的查询投影，供分组列表直接携带计数。 */
package com.example.photomanager.data.database

import androidx.room.Embedded

data class GroupWithAlbumCount(
    @Embedded val group: GroupEntity,
    val albumCount: Int
)
