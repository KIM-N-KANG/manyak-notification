package com.knk.manyak.notification.push

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
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
        assertFailure(send(), 401, "UNAUTHORIZED", "유효하지 않은 인증입니다.")
        assertFailure(send("wrong"), 401, "UNAUTHORIZED", "유효하지 않은 인증입니다.")
        assertThat(send(path = "/internal/other").statusCode()).isEqualTo(401)
    }

    @Test
    fun `경로 파라미터가 있어도 내부 인증을 우회하지 못한다`() {
        assertThat(send(path = "/internal;v=1/notifications").statusCode()).isEqualTo(401)
    }

    @Test
    fun `인증 통과 후 필수 필드가 없으면 400이다`() {
        assertFailure(send("test-secret"), 400, "BAD_REQUEST", "요청 값이 올바르지 않습니다.")
    }

    @Test
    fun `선언적 필드 검증은 field와 message를 담는다`() {
        val response = send("test-secret", """{"recipientId":"00000000-0000-4000-8000-000000000001","kind":"SERVICE","type":" ","data":{}}""")
        val details = assertFailure(response, 400, "BAD_REQUEST", "요청 값이 올바르지 않습니다.", false)
        assertThat(details.size()).isEqualTo(1)
        assertThat(details[0]["field"].asString()).isEqualTo("type")
        assertThat(details[0]["message"].asString()).isNotBlank()
    }

    @Test
    fun `본문 파싱 실패는 빈 details를 반환한다`() {
        listOf("{", "", """{"recipientId":"invalid","kind":"SERVICE","type":"STORY_COMPLETED","data":{}}""").forEach {
            assertFailure(send("test-secret", it), 400, "BAD_REQUEST", "요청 값이 올바르지 않습니다.")
        }
    }

    @Test
    fun `MVC 표준 실패도 실제 HTTP 응답에서 공통 JSON 본문이다`() {
        val cases = listOf(
            Triple("GET", "application/json", 405 to "METHOD_NOT_ALLOWED"),
            Triple("POST", "text/plain", 415 to "UNSUPPORTED_MEDIA_TYPE"),
            Triple("POST", "application/json", 404 to "NOT_FOUND"),
        )
        HttpClient.newHttpClient().use { client ->
            cases.forEach { (method, contentType, expected) ->
                val path = if (expected.first == 404) "/internal/no-mapping" else "/internal/notifications"
                val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                    .header("X-Manyak-Internal-Secret", "test-secret")
                    .header("Content-Type", contentType)
                    .header("X-Manyak-Request-Id", "req-standard-error")
                    .method(method, HttpRequest.BodyPublishers.ofString("{}"))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertThat(response.statusCode()).isEqualTo(expected.first)
                assertThat(response.headers().firstValue("X-Manyak-Request-Id")).hasValue("req-standard-error")
                val body = jacksonObjectMapper().readTree(response.body())
                assertThat(body.properties().map { it.key }).containsExactlyInAnyOrder("timestamp", "status", "code", "message", "path", "details")
                assertThat(body["status"].asInt()).isEqualTo(expected.first)
                assertThat(body["code"].asString()).isEqualTo(expected.second)
                assertThat(body["path"].asString()).isEqualTo(path)
                assertThat(body["details"].size()).isZero()
                if (expected.first == 405) assertThat(response.headers().firstValue("Allow").orElse("")).contains("POST")
            }
        }
    }

    @Test
    fun `필터의 잘못된 form은 Accept와 무관하게 공통 400이다`() {
        listOf("application/json", "image/png").forEach { accept ->
            val response = raw("PUT", accept, "a=%ZZ")
            assertFailure(response, 400, "BAD_REQUEST", "요청 값이 올바르지 않습니다.")
            assertThat(response.headers().firstValue("X-Manyak-Request-Id")).hasValue("req-outside-mvc")
        }
    }

    @Test
    fun `컨테이너 TRACE 거절도 JSON과 상관 헤더 및 Allow를 유지한다`() {
        listOf("application/json", "image/png").forEach { accept ->
            val response = raw("TRACE", accept)
            assertFailure(response, 405, "METHOD_NOT_ALLOWED", "허용되지 않은 요청 메서드입니다.")
            assertThat(response.headers().firstValue("X-Manyak-Request-Id")).hasValue("req-outside-mvc")
            assertThat(response.headers().firstValue("Allow").orElse("")).contains("POST").doesNotContain("TRACE")
        }
    }

    @Test
    fun `컨테이너 거절에 상관 ID가 없으면 생성한다`() {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/internal/notifications"))
            .header("X-Manyak-Internal-Secret", "test-secret")
            .method("TRACE", HttpRequest.BodyPublishers.noBody()).build()
        val response = HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
        assertFailure(response, 405, "METHOD_NOT_ALLOWED", "허용되지 않은 요청 메서드입니다.")
        assertThat(response.headers().firstValue("X-Manyak-Request-Id").orElse("")).matches("req_[0-9a-f]{32}")
    }

    @Test
    fun `잘못된 form도 인증을 먼저 검사한다`() {
        assertFailure(raw("PUT", "image/png", "a=%ZZ", false), 401, "UNAUTHORIZED", "유효하지 않은 인증입니다.")
    }

    private fun raw(method: String, accept: String, body: String = "", authenticated: Boolean = true): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/internal/notifications"))
            .header("Accept", accept).header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-Manyak-Request-Id", "req-outside-mvc")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
        if (authenticated) request.header("X-Manyak-Internal-Secret", "test-secret")
        return HttpClient.newHttpClient().use { it.send(request.build(), HttpResponse.BodyHandlers.ofString()) }
    }

    @Test
    fun `서버 URL 미설정은 발송 없이 502다`() {
        val response = send("test-secret", """{"recipientId":"00000000-0000-4000-8000-000000000001","kind":"SERVICE","type":"STORY_COMPLETED","data":{}}""")
        assertThat(response.statusCode()).isEqualTo(502)
        assertThat(jacksonObjectMapper().readTree(response.body()).properties().map { it.key })
            .containsExactlyInAnyOrder("outcome", "reason", "sent", "unregistered", "failed")
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
                    .header("Accept", "image/png")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .PUT(HttpRequest.BodyPublishers.ofString("a=%ZZ"))
                secret?.let { request.header("X-Manyak-Internal-Secret", it) }
                assertFailure(client.send(request.build(), HttpResponse.BodyHandlers.ofString()), 404, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.")
            }
        }
    }
}

private fun assertFailure(response: HttpResponse<String>, status: Int, code: String, message: String, emptyDetails: Boolean = true): tools.jackson.databind.JsonNode {
    assertThat(response.statusCode()).isEqualTo(status)
    assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json")
    val body = jacksonObjectMapper().readTree(response.body())
    assertThat(body.properties().map { it.key }).containsExactlyInAnyOrder("timestamp", "status", "code", "message", "path", "details")
    assertThat(Instant.parse(body["timestamp"].asString())).isBetween(Instant.now().minusSeconds(60), Instant.now())
    assertThat(body["status"].asInt()).isEqualTo(status)
    assertThat(body["code"].asString()).isEqualTo(code)
    assertThat(body["message"].asString()).isEqualTo(message)
    assertThat(body["path"].asString()).isEqualTo("/internal/notifications")
    assertThat(body["details"].isArray).isTrue()
    if (emptyDetails) assertThat(body["details"].size()).isZero()
    return body["details"]
}
