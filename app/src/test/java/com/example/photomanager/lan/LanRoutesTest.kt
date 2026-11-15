/** M1 路由测试：相册/分组/图片/媒体四个端点，以及排序参数与 Range/HEAD 行为。 */
package com.example.photomanager.lan

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 从响应头读 Content-Length；Ktor 2.3 没有现成的 contentLength() 扩展。 */
private fun HttpResponse.contentLengthOrNull(): Long? =
    headers[HttpHeaders.ContentLength]?.toLong()

class LanRoutesTest {
    // 可注入的时钟：推进 now 就能验证 token 空闲过期与配对超时
    private var now = 0L
    private val tokens = LanTokenStore(clock = { now })
    private val whitelist = InMemoryWhitelist()
    private val pairing = LanPairing(tokens, whitelist, clock = { now })
    private val info = DeviceInfoDto(deviceId = "dev-1", name = "TestPhone", version = "1")

    /** 每次请求都签一个 token；鉴权测试就不需要额外状态。 */
    private fun auth(): String = tokens.issue("dev-1").token

    private fun ApplicationTestBuilder.install(source: ShareDataSource) {
        application {
            lanModule(
                tokens = tokens,
                pairing = pairing,
                deviceInfo = { info },
                dataSource = source
            )
        }
    }

    @Test
    fun `受保护块内所有路由自动鉴权`() = testApplication {
        // 这些路由内部都没有写 requireDevice，靠 authenticated 块统一拦截
        install(FakeShareDataSource())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/groups").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/albums/1/photos").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/media/1").status)
    }

    @Test
    fun `未授权访问相册返回 401`() = testApplication {
        install(FakeShareDataSource())
        val response = client.get("/v1/albums")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `缺省参数使用宿主默认排序`() = testApplication {
        val source = FakeShareDataSource(
            defaultSort = LanSortField.NAME,
            defaultDescending = true
        )
        install(source)
        val response = client.get("/v1/albums") {
            header(TOKEN_HEADER, auth())
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.decodeFromString<LanAlbumListDto>(response.bodyAsText())
        assertEquals(LanSortField.NAME, body.defaultSortField)
        assertTrue(body.defaultSortDescending)
        assertEquals(LanSortField.NAME, body.sortField)
        // 路由没有传参，数据源自己用默认值补齐
        assertNull(source.lastSort)
        assertNull(source.lastDescending)
    }

    @Test
    fun `排序参数透传并回显`() = testApplication {
        val source = FakeShareDataSource()
        install(source)
        val response = client.get("/v1/albums?sort=photoCount&desc=false") {
            header(TOKEN_HEADER, auth())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(LanSortField.PHOTO_COUNT, source.lastSort)
        assertEquals(false, source.lastDescending)

        val body = Json.decodeFromString<LanAlbumListDto>(response.bodyAsText())
        assertEquals(LanSortField.PHOTO_COUNT, body.sortField)
        assertFalse(body.sortDescending)
    }

    @Test
    fun `非法排序字段返回 400`() = testApplication {
        install(FakeShareDataSource())
        val response = client.get("/v1/albums?sort=bogus") {
            header(TOKEN_HEADER, auth())
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("INVALID_SORT"))
    }

    @Test
    fun `非法 desc 返回 400`() = testApplication {
        install(FakeShareDataSource())
        val response = client.get("/v1/albums?desc=maybe") {
            header(TOKEN_HEADER, auth())
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("INVALID_SORT"))
    }

    @Test
    fun `未授权优先于参数校验`() = testApplication {
        install(FakeShareDataSource())
        val response = client.get("/v1/albums?sort=bogus")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `分组返回分组列表与未分组数量`() = testApplication {
        val source = FakeShareDataSource(
            groups = listOf(LanGroupDto(id = 1, name = "g", albumCount = 2)),
            ungroupedCount = 3
        )
        install(source)
        val response = client.get("/v1/groups") {
            header(TOKEN_HEADER, auth())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<LanGroupListDto>(response.bodyAsText())
        assertEquals(1, body.groups.size)
        assertEquals(3, body.ungroupedCount)
    }

    @Test
    fun `图片列表按相册返回`() = testApplication {
        val source = FakeShareDataSource(
            photosByAlbum = mapOf(
                7L to listOf(LanPhotoDto(9, "p.png", 1, 0, 0, null, "/v1/media/9"))
            )
        )
        install(source)
        val response = client.get("/v1/albums/7/photos") {
            header(TOKEN_HEADER, auth())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<List<LanPhotoDto>>(response.bodyAsText())
        assertEquals(1, body.size)
        assertEquals("/v1/media/9", body.first().mediaUrl)
    }

    @Test
    fun `媒体支持 GET、Range 与 HEAD`() = testApplication {
        val file = File.createTempFile("lan-media", ".png").apply {
            writeBytes(ByteArray(8) { it.toByte() })
            deleteOnExit()
        }
        install(FakeShareDataSource(media = mapOf(9L to MediaFile(file.path, "p.png", 8))))

        val get = client.get("/v1/media/9") { header(TOKEN_HEADER, auth()) }
        assertEquals(HttpStatusCode.OK, get.status)
        assertEquals(8L, get.contentLengthOrNull())

        val range = client.get("/v1/media/9") {
            header(TOKEN_HEADER, auth())
            header(HttpHeaders.Range, "bytes=2-5")
        }
        assertEquals(HttpStatusCode.PartialContent, range.status)
        assertEquals("bytes 2-5/8", range.headers[HttpHeaders.ContentRange])
        assertEquals(4, range.body<ByteArray>().size)

        val head = client.head("/v1/media/9") { header(TOKEN_HEADER, auth()) }
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals(8L, head.contentLengthOrNull())
    }

    @Test
    fun `媒体不存在返回 404`() = testApplication {
        install(FakeShareDataSource())
        val response = client.get("/v1/media/404") { header(TOKEN_HEADER, auth()) }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("NOT_FOUND"))
    }

    @Test
    fun `陌生设备配对返回 202 等待确认`() = testApplication {
        install(FakeShareDataSource())
        val response = client.post("/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"dev-2","name":"Guest"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val pending = Json.decodeFromString<LanPairPendingDto>(response.bodyAsText())
        assertTrue(pending.requestId.isNotBlank())
    }

    @Test
    fun `批准后轮询拿到 token 并可访问相册`() = testApplication {
        install(FakeShareDataSource())
        val pending = Json.decodeFromString<LanPairPendingDto>(
            client.post("/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody("""{"deviceId":"dev-2","name":"Guest"}""")
            }.bodyAsText()
        )
        // 主机在弹窗里点"允许"并勾选"记住"
        pairing.approve(pending.requestId, remember = true)

        val polled = client.get("/v1/pair/${pending.requestId}")
        assertEquals(HttpStatusCode.OK, polled.status)
        val issued = Json.decodeFromString<LanPairResponseDto>(polled.bodyAsText())
        assertTrue(whitelist.contains("dev-2"))

        // 用新 token 访问受保护接口
        val albums = client.get("/v1/albums") { header(TOKEN_HEADER, issued.token) }
        assertEquals(HttpStatusCode.OK, albums.status)
    }

    @Test
    fun `拒绝配对返回 403`() = testApplication {
        install(FakeShareDataSource())
        val pending = Json.decodeFromString<LanPairPendingDto>(
            client.post("/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody("""{"deviceId":"dev-2","name":"Guest"}""")
            }.bodyAsText()
        )
        pairing.reject(pending.requestId)

        val polled = client.get("/v1/pair/${pending.requestId}")
        assertEquals(HttpStatusCode.Forbidden, polled.status)
        assertTrue(polled.bodyAsText().contains("PAIR_REJECTED"))
    }

    @Test
    fun `配对超时返回 409`() = testApplication {
        install(FakeShareDataSource())
        val pending = Json.decodeFromString<LanPairPendingDto>(
            client.post("/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody("""{"deviceId":"dev-2","name":"Guest"}""")
            }.bodyAsText()
        )
        // 推进注入的时钟，越过 60 秒等待窗口
        now += LanPairing.DEFAULT_PAIR_TIMEOUT_MILLIS + 1

        val polled = client.get("/v1/pair/${pending.requestId}")
        assertEquals(HttpStatusCode.Conflict, polled.status)
        assertTrue(polled.bodyAsText().contains("PAIR_TIMEOUT"))
    }

    @Test
    fun `白名单设备免确认`() = testApplication {
        whitelist.add("dev-4", "Known")
        install(FakeShareDataSource())
        val response = client.post("/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"dev-4","name":"Known"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
