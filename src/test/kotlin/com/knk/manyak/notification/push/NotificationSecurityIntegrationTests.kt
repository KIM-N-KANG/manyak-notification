package com.knk.manyak.notification.push

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.internal.shared-secret=test-secret", "manyak.server.internal-base-url=", "manyak.push.fcm.service-account-json="])
class NotificationSecurityIntegrationTests {
    @LocalServerPort private var port = 0

    private fun send(secret: String? = null, body: String = "{}", path: String = "/internal/notifications"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        secret?.let { request.header("X-Manyak-Internal-Secret", it) }
        return HttpClient.newHttpClient().use { it.send(request.build(), HttpResponse.BodyHandlers.ofString()) }
    }

    @Test
    fun `시크릿 누락과 불일치는 입력 검증보다 먼저 401이다`() {
        assertThat(send().statusCode()).isEqualTo(401)
        assertThat(send("wrong").statusCode()).isEqualTo(401)
        assertThat(send(path = "/internal/other").statusCode()).isEqualTo(401)
    }

    @Test
    fun `경로 파라미터가 있어도 내부 인증을 우회하지 못한다`() {
        assertThat(send(path = "/internal;v=1/notifications").statusCode()).isEqualTo(401)
    }

    @Test
    fun `인증 통과 후 필수 필드가 없으면 400이다`() {
        assertThat(send("test-secret").statusCode()).isEqualTo(400)
    }

    @Test
    fun `서버 URL 미설정은 발송 없이 502다`() {
        val response = send("test-secret", """{"recipientId":"00000000-0000-4000-8000-000000000001","kind":"SERVICE","type":"STORY_COMPLETED","data":{}}""")
        assertThat(response.statusCode()).isEqualTo(502)
        assertThat(response.body()).contains("ELIGIBILITY_UNAVAILABLE", "FAILED")
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.internal.shared-secret=", "manyak.server.internal-base-url=", "manyak.push.fcm.service-account-json="])
class NotificationDisabledIntegrationTests {
    @LocalServerPort private var port = 0

    @Test
    fun `시크릿 미설정이면 헤더와 본문에 상관없이 404다`() {
        HttpClient.newHttpClient().use { client ->
            listOf(null, "arbitrary-secret").forEach { secret ->
                val request = HttpRequest.newBuilder(URI("http://localhost:$port/internal/notifications"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                secret?.let { request.header("X-Manyak-Internal-Secret", it) }
                assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404)
            }
        }
    }
}
