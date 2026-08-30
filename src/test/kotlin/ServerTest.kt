package moe.lolosia.web

/**
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 11:42
 */

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*
import moe.lolosia.web.config.web.configureApplication

class ServerTest {

    @Test
    fun `test root endpoint`() = testApplication {
        application {
            configureApplication()
        }
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

    @Test
    fun `test greet endpoint`() = testApplication {
        application {
            configureApplication()
        }
        val response = client.get("/greet/world?lang=zh")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Hello, world"))
    }
}
