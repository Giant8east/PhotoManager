/** 访客侧门面：一次配对 + 三个只读查询；401 时自动重新配对一次。 */
package com.giant8east.photomanager.lan.client

import com.giant8east.photomanager.model.AlbumSortField
import com.giant8east.photomanager.lan.protocol.API_PREFIX
import com.giant8east.photomanager.lan.protocol.DEFAULT_PAGE_LIMIT
import com.giant8east.photomanager.lan.protocol.LanAlbumListDto
import com.giant8east.photomanager.lan.protocol.LanGroupListDto
import com.giant8east.photomanager.lan.protocol.LanPhotoDto
import com.giant8east.photomanager.lan.protocol.LanDevice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

@Singleton
class LanClient @Inject constructor(
    private val http: LanHttp,
    private val pairing: PairingFlow
) {
    /** 配对并拿到 token；已配对过则直接复用。 */
    suspend fun connect(device: LanDevice): String = pairing.connect(device)

    suspend fun albums(
        device: LanDevice,
        sortField: AlbumSortField? = null,
        descending: Boolean? = null,
        query: String? = null,
        groupId: Long? = null,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): LanAlbumListDto = withAuth(device) {
        http.api.albums(
            http.url(device.address, "$API_PREFIX/albums"),
            sortField?.wire,
            descending,
            query,
            groupId,
            offset,
            limit
        )
    }

    suspend fun groups(device: LanDevice): LanGroupListDto = withAuth(device) {
        http.api.groups(http.url(device.address, "$API_PREFIX/groups"))
    }

    suspend fun photos(device: LanDevice, albumId: Long): List<LanPhotoDto> = withAuth(device) {
        http.api.photos(http.url(device.address, "$API_PREFIX/albums/$albumId/photos"))
    }

    /**
     * 401 说明 token 过期或被撤销：丢掉旧 token，重新配对后重试一次。
     * 第二次仍失败就抛出去，由 ViewModel 统一转成一行提示。
     */
    private suspend fun <T> withAuth(device: LanDevice, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            runCatching {
                pairing.connect(device)
                block()
            }.getOrElse { error ->
                if (error is HttpException && error.code() == 401) {
                    pairing.reconnect(device)
                    block()
                } else {
                    throw error
                }
            }
        }
}
