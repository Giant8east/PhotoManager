/** 协议级单测：用 testApplication 直接喂请求给引擎，验证开放接口与 token 鉴权。 */
package com.example.photomanager.lan

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语法糖说明：
 * - 反引号函数名 ``fun `ping 不需要 token`()`` 是 Kotlin 的写法，允许测试名里带空格和中文；
 * - `= testApplication { ... }` 是单表达式函数：等价于 `{ testApplication { ... } }`；
 * - `testApplication` 不会真的监听端口，它把请求直接喂给引擎，所以测试快且不依赖网络。
 */
class LanServerTest {
    private val tokens = LanTokenStore()
    private val info = DeviceInfoDto(deviceId = "dev-1", name = "TestPhone", version = "1")

    /** 在每个测试里安装同一个服务端模块；`application { }` 里的 this 是 Application。 */
    private fun ApplicationTestBuilder.installModule() {
        application {
            // 具名参数：显式写出参数名，避免位置参数看错
            lanModule(
                tokens = tokens,
                pairing = LanPairing(tokens, InMemoryWhitelist()),
                deviceInfo = { info },
                dataSource = FakeShareDataSource()
            )
        }
    }

    @Test
    fun `ping 不需要 token`() = testApplication {
        installModule()
        // client 是测试框架提供的 HTTP 客户端，get 发起 GET 请求
        val response = client.get("/v1/ping")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("TestPhone"))
    }

    @Test
    fun `缺少 token 时受保护接口返回 401`() = testApplication {
        installModule()
        val response = client.get("/v1/session")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("UNAUTHORIZED"))
    }

    @Test
    fun `错误 token 同样返回 401`() = testApplication {
        installModule()
        tokens.issue("dev-1").token
        // `get(url) { header(...) }` 的第二个 lambda 用来配置这次请求
        val response = client.get("/v1/session") {
            header(TOKEN_HEADER, "not-a-real-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `有效 token 返回设备身份`() = testApplication {
        installModule()
        val token = tokens.issue("dev-1").token
        val response = client.get("/v1/session") {
            header(TOKEN_HEADER, token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("dev-1"))
    }

    @Test
    fun `撤销设备后旧 token 立即失效`() = testApplication {
        installModule()
        val token = tokens.issue("dev-1").token
        tokens.revoke("dev-1")
        val response = client.get("/v1/session") {
            header(TOKEN_HEADER, token)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `重复签发让旧 token 失效`() = testApplication {
        installModule()
        val first = tokens.issue("dev-1").token
        tokens.issue("dev-1").token
        val response = client.get("/v1/session") {
            header(TOKEN_HEADER, first)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
