/** M0 契约测试：校验线上 DTO 的字段名与嵌套结构，并用假数据源验证 ShareDataSource 可替换。 */
package com.example.photomanager.lan

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDtosTest {
    private val json = Json

    // kotlinx.serialization 会生成对象的 JSON；这里把 key 集合当作"协议契约"断言，
    // 字段改名或漏加字段时测试会立刻失败。
    @Test
    fun `相册字段与协议一致`() {
        val dto = LanAlbumDto(
            id = 1,
            name = "album",
            photoCount = 2,
            sizeBytes = 3,
            groupId = null,
            coverUrl = "/v1/media/9",
            modifiedAt = "2025-01-01T00:00:00Z"
        )
        val obj = json.parseToJsonElement(json.encodeToString(dto)).jsonObject
        assertEquals(
            setOf("id", "name", "photoCount", "sizeBytes", "groupId", "coverUrl", "modifiedAt"),
            obj.keys
        )
        // null 也必须出现在 JSON 里：客户端才能区分"字段缺失"和"值为空"
        assertEquals(JsonNull, obj["groupId"])
        assertEquals(dto, json.decodeFromString<LanAlbumDto>(json.encodeToString(dto)))
    }

    @Test
    fun `图片字段与协议一致`() {
        val dto = LanPhotoDto(
            id = 7,
            name = "a.png",
            sizeBytes = 1024,
            width = 800,
            height = 1200,
            format = "png",
            mediaUrl = "/v1/media/7"
        )
        val obj = json.parseToJsonElement(json.encodeToString(dto)).jsonObject
        assertEquals(
            setOf("id", "name", "sizeBytes", "width", "height", "format", "mediaUrl"),
            obj.keys
        )
        assertEquals(dto, json.decodeFromString<LanPhotoDto>(json.encodeToString(dto)))
    }

    @Test
    fun `分组列表支持嵌套与未分组计数`() {
        val dto = LanGroupListDto(
            groups = listOf(LanGroupDto(id = 1, name = "g", albumCount = 2)),
            ungroupedCount = 3
        )
        val decoded = json.decodeFromString<LanGroupListDto>(json.encodeToString(dto))
        assertEquals(dto, decoded)
        assertEquals(1, decoded.groups.size)
        assertEquals(3, decoded.ungroupedCount)
    }

    @Test
    fun `相册列表携带默认排序与实际排序`() {
        val dto = LanAlbumListDto(
            albums = listOf(
                LanAlbumDto(1, "a", 2, 3, groupId = null, coverUrl = null, modifiedAt = null)
            ),
            defaultSortField = LanSortField.CUSTOM,
            defaultSortDescending = false,
            sortField = LanSortField.NAME,
            sortDescending = true
        )
        val encoded = json.encodeToString(dto)
        // 枚举必须序列化成 wire 值，而不是枚举名（CUSTOM / NAME）
        assertTrue(encoded.contains("\"defaultSortField\":\"custom\""))
        assertTrue(encoded.contains("\"sortField\":\"name\""))

        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals(
            setOf("albums", "defaultSortField", "defaultSortDescending", "sortField", "sortDescending"),
            obj.keys
        )
        assertEquals(dto, json.decodeFromString<LanAlbumListDto>(encoded))
    }

    @Test
    fun `排序字段的 wire 值映射与大小写敏感`() {
        assertEquals(LanSortField.CUSTOM, LanSortField.fromWire("custom"))
        assertEquals(LanSortField.NAME, LanSortField.fromWire("name"))
        assertEquals(LanSortField.MODIFIED, LanSortField.fromWire("modified"))
        assertEquals(LanSortField.PHOTO_COUNT, LanSortField.fromWire("photoCount"))
        assertEquals(LanSortField.FILE_SIZE, LanSortField.fromWire("fileSize"))

        // 大小写敏感、未知值都返回 null，由路由决定是否 400
        assertNull(LanSortField.fromWire("CUSTOM"))
        assertNull(LanSortField.fromWire("photocount"))
        assertNull(LanSortField.fromWire("bogus"))
        assertNull(LanSortField.fromWire(""))
    }

    @Test
    fun `排序字段的默认方向与本地一致`() {
        assertEquals(false, LanSortField.CUSTOM.defaultDescending)
        assertEquals(false, LanSortField.NAME.defaultDescending)
        assertEquals(true, LanSortField.MODIFIED.defaultDescending)
        assertEquals(true, LanSortField.PHOTO_COUNT.defaultDescending)
        assertEquals(true, LanSortField.FILE_SIZE.defaultDescending)
    }

    @Test
    fun `错误码序列化为协议字符串`() {
        // 枚举名就是线上值：重命名枚举项等于改协议，这条测试会拦住无意改名
        LanErrorCode.entries.forEach { code ->
            assertEquals("\"${code.name}\"", json.encodeToString(code))
        }
    }

    @Test
    fun `错误响应可往返且包含业务码`() {
        val dto = ErrorDto(code = LanErrorCode.INVALID_ID, message = "album id must be a number")
        val encoded = json.encodeToString(dto)
        assertTrue(encoded.contains("\"code\":\"INVALID_ID\""))
        assertEquals(dto, json.decodeFromString<ErrorDto>(encoded))
    }

    @Test
    fun `配对请求与响应可往返`() {
        val request = LanPairRequestDto(deviceId = "dev-1", name = "Phone")
        assertEquals(request, json.decodeFromString<LanPairRequestDto>(json.encodeToString(request)))

        val response = LanPairResponseDto(token = "abc")
        assertEquals(response, json.decodeFromString<LanPairResponseDto>(json.encodeToString(response)))

        val pending = LanPairPendingDto(requestId = "req-1")
        assertEquals(pending, json.decodeFromString<LanPairPendingDto>(json.encodeToString(pending)))
    }

    // 假数据源：证明路由层只依赖接口，测试与 Android 实现可以互换。
    @Test
    fun `假数据源可以替代 Android 实现`() = runBlocking {
        val source = object : ShareDataSource {
            override suspend fun albums(sortField: LanSortField?, descending: Boolean?): LanAlbumListDto =
                LanAlbumListDto(
                    albums = listOf(
                        LanAlbumDto(1, "a", 2, 3, groupId = null, coverUrl = null, modifiedAt = null)
                    ),
                    defaultSortField = LanSortField.CUSTOM,
                    defaultSortDescending = false,
                    sortField = sortField ?: LanSortField.CUSTOM,
                    sortDescending = descending ?: false
                )

            override suspend fun groups(): LanGroupListDto =
                LanGroupListDto(groups = emptyList(), ungroupedCount = 1)

            override suspend fun photos(albumId: Long): List<LanPhotoDto> =
                listOf(LanPhotoDto(9, "p.png", 1, 0, 0, null, "/v1/media/9"))

            override suspend fun mediaFile(photoId: Long): MediaFile? =
                if (photoId == 9L) MediaFile(path = "/tmp/p.png", name = "p.png", sizeBytes = 1) else null
        }

        val listed = source.albums(LanSortField.NAME, descending = true)
        assertEquals(1, listed.albums.size)
        assertEquals(LanSortField.NAME, listed.sortField)
        assertTrue(listed.sortDescending)
        assertEquals(1, source.groups().ungroupedCount)
        assertEquals(1, source.photos(1).size)
        assertEquals("p.png", source.mediaFile(9)?.name)
        assertNull(source.mediaFile(404))
        assertTrue(source.mediaFile(9)?.path?.isNotEmpty() == true)
    }
}
