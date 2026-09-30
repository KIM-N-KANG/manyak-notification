package com.knk.manyak.notification.push

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.knk.manyak.notification.push.client.PushEligibilityClient
import com.knk.manyak.notification.push.config.PushClientConfig
import com.knk.manyak.notification.push.controller.NotificationController
import com.knk.manyak.notification.push.service.FcmPushSender
import com.knk.manyak.notification.push.service.NotificationService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.slf4j.MDC
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.anything
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID

class NotificationControllerTest {
    private val recipient = UUID.randomUUID()
    private val builder = PushClientConfig().serverRestClientBuilder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = PushEligibilityClient(builder, "http://server", "test-secret")
    private val messaging = mock(FirebaseMessaging::class.java)
    private val registry = SimpleMeterRegistry()
    private val mvc = controller(messaging)

    private fun controller(firebase: FirebaseMessaging?): MockMvc = MockMvcBuilders.standaloneSetup(
        NotificationController(NotificationService(client, FcmPushSender(firebase, client, registry))),
    ).build()

    private fun body(extra: String = "") = """{"recipientId":"$recipient","kind":"SERVICE","type":"STORY_COMPLETED","data":{"title":"완성","body":"내용","recipientId":"spoofed","type":"spoofed"}$extra}"""
    private fun send(body: String = body(), target: MockMvc = mvc) = target.perform(
        post("/internal/notifications").contentType(MediaType.APPLICATION_JSON).content(body),
    )

    private fun eligibility(response: String) {
        server.expect { request ->
            assertThat(request.uri.path).isEqualTo("/internal/users/$recipient/push-eligibility")
            assertThat(request.uri.query).contains("kind=SERVICE", "at=")
        }.andExpect(method(HttpMethod.GET)).andExpect(header("X-Manyak-Internal-Secret", "test-secret"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
    }

    private fun allowed(count: Int = 2) = eligibility("""{"allowed":true,"reason":"OK","tokens":[${(1..count).joinToString(",") { """{"token":"token-$it","platform":"ANDROID"}""" }}]}""")

    @AfterEach
    fun tearDown() {
        server.verify()
        MDC.clear()
    }

    @Test
    fun `자격 거절은 발송하지 않는다`() {
        eligibility("""{"allowed":false,"reason":"SERVICE_PUSH_DISABLED","tokens":[]}""")
        send().andExpect(status().isOk).andExpect(jsonPath("$.outcome").value("SKIPPED"))
            .andExpect(jsonPath("$.reason").value("SERVICE_PUSH_DISABLED"))
        verifyNoInteractions(messaging)
    }

    @Test
    fun `자격 조회 5xx는 502이고 발송하지 않는다`() {
        server.expect(anything()).andRespond(withServerError())
        send().andExpect(status().isBadGateway).andExpect(jsonPath("$.outcome").value("FAILED"))
        verifyNoInteractions(messaging)
    }

    @Test
    fun `자격 조회 타임아웃은 502이고 발송하지 않는다`() {
        server.expect(anything()).andRespond(withException(SocketTimeoutException("test timeout")))
        send().andExpect(status().isBadGateway).andExpect(jsonPath("$.outcome").value("FAILED"))
        verifyNoInteractions(messaging)
    }

    @Test
    fun `허용된 두 기기에 수신자와 유형을 덧붙여 보낸다`() {
        allowed()
        send().andExpect(status().isOk).andExpect(jsonPath("$.outcome").value("SENT"))
            .andExpect(jsonPath("$.sent").value(2))
        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging, times(2)).send(captor.capture())
        val field = Message::class.java.getDeclaredField("data").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val data = field.get(captor.value) as Map<String, String>
        assertThat(data).containsEntry("recipientId", recipient.toString()).containsEntry("type", "STORY_COMPLETED")
        assertThat(registry.get(FcmPushSender.METRIC_PUSH_SEND_RESULT).tag("outcome", "success").counter().count()).isEqualTo(2.0)
    }

    private fun failFirst(code: MessagingErrorCode) {
        val exception = mock(FirebaseMessagingException::class.java)
        `when`(exception.messagingErrorCode).thenReturn(code)
        `when`(messaging.send(any(Message::class.java))).thenThrow(exception).thenReturn("ok")
    }

    @Test
    fun `UNREGISTERED만 삭제하고 나머지 두 기기에 보낸다`() {
        allowed(3)
        MDC.put("request_id", "req-cleanup")
        failFirst(MessagingErrorCode.UNREGISTERED)
        server.expect(requestTo("http://server/internal/push-tokens")).andExpect(method(HttpMethod.DELETE))
            .andExpect(header("X-Manyak-Internal-Secret", "test-secret"))
            .andExpect(header("X-Manyak-Request-Id", "req-cleanup"))
            .andExpect(content().json("""{"token":"token-1"}""")).andRespond(withNoContent())
        send().andExpect(status().isOk).andExpect(jsonPath("$.sent").value(2))
            .andExpect(jsonPath("$.unregistered").value(1)).andExpect(jsonPath("$.failed").value(0))
        verify(messaging, times(3)).send(any(Message::class.java))
    }

    @Test
    fun `INVALID_ARGUMENT는 토큰을 지우지 않는다`() {
        allowed()
        failFirst(MessagingErrorCode.INVALID_ARGUMENT)
        send().andExpect(status().isOk).andExpect(jsonPath("$.sent").value(1)).andExpect(jsonPath("$.failed").value(1))
        verify(messaging, times(2)).send(any(Message::class.java))
    }

    @Test
    fun `삭제 실패도 다음 기기 발송을 막지 않는다`() {
        allowed(3)
        failFirst(MessagingErrorCode.UNREGISTERED)
        server.expect(requestTo("http://server/internal/push-tokens")).andRespond(withServerError())
        send().andExpect(status().isOk).andExpect(jsonPath("$.sent").value(2)).andExpect(jsonPath("$.failed").value(1))
        verify(messaging, times(3)).send(any(Message::class.java))
    }

    @Test
    fun `만료는 자격 조회도 발송도 하지 않는다`() {
        send(body(",\"expiresAt\":\"2000-01-01T00:00:00Z\""))
            .andExpect(status().isOk).andExpect(jsonPath("$.outcome").value("EXPIRED"))
        verifyNoInteractions(messaging)
    }

    @Test
    fun `현재 시각과 상관 헤더를 자격 API에 전달한다`() {
        val before = Instant.now()
        MDC.put("request_id", "req-test")
        MDC.put("session_id", "session-test")
        MDC.put("device_id_hash", "hash-test")
        server.expect { request ->
            val at = Instant.parse(request.uri.query.substringAfter("at="))
            assertThat(at).isBetween(before, Instant.now())
        }.andExpect(header("X-Manyak-Internal-Secret", "test-secret"))
            .andExpect(header("X-Manyak-Request-Id", "req-test"))
            .andExpect(header("X-Manyak-Session-Id", "session-test"))
            .andExpect(header("X-Manyak-Device-Id-Hash", "hash-test"))
            .andRespond(withSuccess("""{"allowed":false,"reason":"NO_TOKENS","tokens":[]}""", MediaType.APPLICATION_JSON))
        send().andExpect(status().isOk)
    }

    @Test
    fun `unknown과 없는 상관 헤더는 생략한다`() {
        MDC.put("session_id", "unknown")
        server.expect(anything()).andExpect(headerDoesNotExist("X-Manyak-Request-Id"))
            .andExpect(headerDoesNotExist("X-Manyak-Session-Id"))
            .andExpect(headerDoesNotExist("X-Manyak-Device-Id-Hash"))
            .andRespond(withSuccess("""{"allowed":false,"reason":"NO_TOKENS","tokens":[]}""", MediaType.APPLICATION_JSON))
        send().andExpect(status().isOk)
    }

    @ParameterizedTest
    @ValueSource(strings = ["recipientId", "kind", "type", "data"])
    fun `필수 필드 누락은 400이다`(field: String) {
        val fields = linkedMapOf("recipientId" to "\"$recipient\"", "kind" to "\"SERVICE\"", "type" to "\"STORY_COMPLETED\"", "data" to "{}")
        fields.remove(field)
        send(fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }).andExpect(status().isBadRequest)
        verifyNoInteractions(messaging)
    }

    @Test
    fun `자격 응답 본문이 없으면 발송 없이 502다`() {
        server.expect(anything()).andRespond(withNoContent())
        send().andExpect(status().isBadGateway).andExpect(jsonPath("$.outcome").value("FAILED"))
        verifyNoInteractions(messaging)
    }

    @Test
    fun `광고 알림은 NORMAL이고 만료 시각을 TTL로 전달한다`() {
        val expiresAt = Instant.now().plusSeconds(60)
        server.expect { request -> assertThat(request.uri.query).contains("kind=MARKETING") }
            .andRespond(withSuccess("""{"allowed":true,"reason":"OK","tokens":[{"token":"token","platform":"ANDROID"}]}""", MediaType.APPLICATION_JSON))
        send(body(",\"expiresAt\":\"$expiresAt\"").replace("\"SERVICE\"", "\"MARKETING\""))
            .andExpect(status().isOk).andExpect(jsonPath("$.sent").value(1))
        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
        val android = field(captor.value, "androidConfig")
        assertThat(field(android, "priority")).isEqualTo("normal")
        val seconds = (field(android, "ttl") as String).removeSuffix("s").toDouble()
        assertThat(seconds).isGreaterThan(0.0).isLessThanOrEqualTo(60.0)
    }

    @Test
    fun `FCM 전체 실패도 요약을 담아 200이다`() {
        allowed(1)
        failFirst(MessagingErrorCode.INVALID_ARGUMENT)
        send().andExpect(status().isOk).andExpect(jsonPath("$.outcome").value("FAILED"))
            .andExpect(jsonPath("$.sent").value(0)).andExpect(jsonPath("$.failed").value(1))
    }

    @Test
    fun `빈 유형은 400이다`() {
        send(body().replace("STORY_COMPLETED", " ")).andExpect(status().isBadRequest)
        verifyNoInteractions(messaging)
    }

    @Test
    fun `Firebase 미설정은 응답에 드러난다`() {
        allowed()
        send(target = controller(null)).andExpect(status().isOk).andExpect(jsonPath("$.outcome").value("SKIPPED"))
            .andExpect(jsonPath("$.reason").value("FCM_DISABLED")).andExpect(jsonPath("$.sent").value(0))
        verifyNoInteractions(messaging)
    }
}
