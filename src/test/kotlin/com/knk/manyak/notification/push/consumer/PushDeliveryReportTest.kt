package com.knk.manyak.notification.push.consumer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.firebase.IncomingHttpResponse
import com.google.firebase.messaging.*
import com.knk.manyak.notification.push.client.PushDeliveryClient
import com.knk.manyak.notification.push.client.PushEligibilityClient
import com.knk.manyak.notification.push.config.PushClientConfig
import com.knk.manyak.notification.push.dto.*
import com.knk.manyak.notification.push.service.*
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class PushDeliveryReportTest {
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val eligibility = mock(PushEligibilityClient::class.java)
    private val messaging = mock(FirebaseMessaging::class.java)
    private val registry = SimpleMeterRegistry()
    private val store = NotificationConsumerTest.MemoryStore()
    private val bodies = CopyOnWriteArrayList<JsonNode>()
    private val headers = CopyOnWriteArrayList<Map<String, List<String>>>()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var status = 204
    private var delayMs = 0L
    private var beforeReport: () -> Unit = {}
    private val message = PushMessage("id", UUID.randomUUID(), PushKind.SERVICE, "STORY_COMPLETED",
        mapOf("type" to "STORY_COMPLETED"), null, "request", "session", 1)
    private lateinit var consumer: NotificationConsumer
    private val logger = LoggerFactory.getLogger(PushDeliveryClient::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach fun setup() {
        server.executor = executor
        server.createContext("/internal/push-deliveries") { exchange ->
            bodies.add(JsonMapper.builder().build().readTree(exchange.requestBody))
            headers.add(exchange.requestHeaders.toMap())
            beforeReport()
            if (delayMs > 0) Thread.sleep(delayMs)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        logger.addAppender(logs)
        consumer = NotificationConsumer(
            NotificationService(eligibility, FcmPushSender(messaging, eligibility, registry, clock = clock), clock),
            store, registry, PushDeliveryClient(PushClientConfig().serverRestClientBuilder(ObservationRegistry.NOOP),
                "http://127.0.0.1:${server.address.port}", "test-shared-secret"), clock)
        allow()
    }

    @AfterEach fun close() {
        server.stop(0)
        executor.shutdownNow()
        logger.detachAppender(logs)
        logs.stop()
    }

    private fun allow(tokens: List<String> = listOf("a", "b")) {
        doReturn(PushEligibilityResponse(true, "OK", tokens.map { PushEligibilityToken(it, PushPlatform.ANDROID) }))
            .`when`(eligibility).getEligibility(anyValue(), anyValue(), anyValue())
    }

    private fun error(code: MessagingErrorCode, status: Int): FirebaseMessagingException {
        val ex = mock(FirebaseMessagingException::class.java)
        `when`(ex.messagingErrorCode).thenReturn(code)
        val response = mock(IncomingHttpResponse::class.java)
        `when`(response.statusCode).thenReturn(status)
        `when`(ex.httpResponse).thenReturn(response)
        return ex
    }

    private fun assertReport(outcome: String, delivered: Int, failed: Int, reason: String) {
        assertThat(bodies).hasSize(1)
        val report = bodies.single()
        assertThat(report["outcome"].stringValue()).isEqualTo(outcome)
        assertThat(report["deliveredDevices"].asInt()).isEqualTo(delivered)
        assertThat(report["failedDevices"].asInt()).isEqualTo(failed)
        assertThat(report["reason"].stringValue()).isEqualTo(reason)
        assertThat(report["completedAt"].stringValue()).isEqualTo(now.toString())
    }

    @Test fun `완료 기록 뒤 전체 필드와 인증 상관 헤더를 보고하고 완료 재수신에는 보고하지 않는다`() {
        beforeReport = { assertThat(store.state).isEqualTo(Claim.DONE) }
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.SUCCESS)
        assertReport("DELIVERED", 2, 0, "OK")
        val report = bodies.single()
        assertThat(report["messageId"].stringValue()).isEqualTo(message.messageId)
        assertThat(report["type"].stringValue()).isEqualTo(message.type)
        assertThat(report["kind"].stringValue()).isEqualTo("SERVICE")
        assertThat(report["recipientId"].stringValue()).isEqualTo(message.recipientId.toString())
        assertThat(report["campaignId"]?.isNull ?: true).isTrue()
        val normalized = headers.single().mapKeys { it.key.lowercase() }
        assertThat(normalized["x-manyak-internal-secret"]).containsExactly("test-shared-secret")
        assertThat(normalized["x-manyak-request-id"]).containsExactly("request")
        assertThat(normalized["x-manyak-session-id"]).containsExactly("session")
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.SUCCESS)
        assertThat(bodies).hasSize(1)
    }

    @Test fun `영구 실패와 무효 토큰은 모두 실패 기기로 보고한다`() {
        val invalid = error(MessagingErrorCode.INVALID_ARGUMENT, 400)
        val unregistered = error(MessagingErrorCode.UNREGISTERED, 404)
        `when`(messaging.send(any(Message::class.java))).thenThrow(invalid).thenThrow(unregistered)
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.DISCARD)
        assertReport("UNDELIVERED", 0, 2, "NO_DELIVERIES")
    }

    @Test fun `만료와 자격 거절은 발송 없이 폐기 사유를 보존한다`() {
        assertThat(consumer.onMessage(message.copy(expiresAt = Instant.EPOCH))).isEqualTo(ConsumeResult.DISCARD)
        assertReport("DISCARDED", 0, 0, "EXPIRED")
        bodies.clear()
        store.state = Claim.ACQUIRED
        `when`(eligibility.getEligibility(anyValue(), anyValue(), anyValue()))
            .thenReturn(PushEligibilityResponse(false, "MARKETING_NOT_AGREED", emptyList()))
        assertThat(consumer.onMessage(message.copy(type = "PROMOTION", kind = PushKind.MARKETING))).isEqualTo(ConsumeResult.DISCARD)
        assertReport("DISCARDED", 0, 0, "MARKETING_NOT_AGREED")
        verifyNoInteractions(messaging)
    }

    @Test fun `재전달은 보고하지 않고 마지막 보고는 이전 처리의 성공 집합도 포함한다`() {
        val unavailable = error(MessagingErrorCode.UNAVAILABLE, 503)
        `when`(messaging.send(any(Message::class.java))).thenReturn("sent")
            .thenThrow(unavailable).thenReturn("sent")
        val campaign = UUID.randomUUID().toString()
        val promotion = message.copy(type = "PROMOTION", kind = PushKind.MARKETING,
            data = mapOf("type" to "PROMOTION", "campaignId" to campaign))
        assertThat(consumer.onMessage(promotion)).isEqualTo(ConsumeResult.RETRY)
        assertThat(bodies).isEmpty()
        // 자격 조회 목록에서 이전 성공 토큰이 사라져도 누적 성공 수는 유지한다.
        allow(listOf("b"))
        assertThat(consumer.onMessage(promotion)).isEqualTo(ConsumeResult.SUCCESS)
        assertReport("DELIVERED", 2, 0, "OK")
        assertThat(bodies.single()["campaignId"].stringValue()).isEqualTo(campaign)
        verify(messaging, times(3)).send(any(Message::class.java))
    }

    @Test fun `선점 중이거나 자격 조회 실패 또는 완료 기록 실패면 보고하지 않는다`() {
        store.state = Claim.BUSY
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.RETRY)
        store.state = Claim.ACQUIRED
        `when`(eligibility.getEligibility(anyValue(), anyValue(), anyValue())).thenThrow(IllegalStateException())
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.RETRY)
        allow()
        val failing = object : DeliveryStore by store {
            override fun complete(messageId: String, owner: String) { throw IllegalStateException("Redis unavailable") }
        }
        val target = NotificationConsumer(NotificationService(eligibility,
            FcmPushSender(messaging, eligibility, registry, clock = clock), clock), failing, registry,
            PushDeliveryClient(PushClientConfig().serverRestClientBuilder(ObservationRegistry.NOOP),
                "http://127.0.0.1:${server.address.port}", "test-shared-secret"), clock)
        assertThat(target.onMessage(message)).isEqualTo(ConsumeResult.RETRY)
        assertThat(bodies).isEmpty()
    }

    @Test fun `일부 도달과 영구 실패는 도달 결과에 마지막 실패 기기 수를 함께 보고한다`() {
        val invalid = error(MessagingErrorCode.INVALID_ARGUMENT, 400)
        `when`(messaging.send(any(Message::class.java))).thenReturn("sent").thenThrow(invalid)
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.SUCCESS)
        assertReport("DELIVERED", 1, 1, "PARTIAL_FAILURE")
    }

    @Test fun `서버 연결 실패는 경고만 남기고 완료 기록을 유지한다`() {
        server.stop(0)
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.SUCCESS)
        assertThat(store.state).isEqualTo(Claim.DONE)
        assertThat(logs.list.filter { it.level == Level.WARN }.single().formattedMessage)
            .contains("push_delivery_report_failed", "ResourceAccessException")
        consumer.onMessage(message)
        assertThat(logs.list.filter { it.level == Level.WARN }).hasSize(1)
    }

    @ParameterizedTest @ValueSource(ints = [401, 404, 500])
    fun `HTTP 보고 오류는 WARN만 남기고 소비 성공과 완료 기록을 유지한다`(httpStatus: Int) {
        status = httpStatus
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.SUCCESS)
        assertThat(store.state).isEqualTo(Claim.DONE)
        assertThat(bodies).hasSize(1)
        assertThat(logs.list.filter { it.level == Level.WARN }.single().formattedMessage)
            .contains("push_delivery_report_failed", "messageId=id", "error=$httpStatus")
        consumer.onMessage(message)
        assertThat(bodies).hasSize(1)
    }

    @Test fun `읽기 2초 초과는 WARN만 남기고 폐기 결과를 유지하며 재시도하지 않는다`() {
        delayMs = 3000
        val start = System.nanoTime()
        assertThat(consumer.onMessage(message.copy(expiresAt = Instant.EPOCH))).isEqualTo(ConsumeResult.DISCARD)
        assertThat((System.nanoTime() - start) / 1_000_000).isBetween(1800L, 2900L)
        assertThat(store.state).isEqualTo(Claim.DONE)
        assertThat(bodies).hasSize(1)
        assertThat(logs.list.filter { it.level == Level.WARN }.single().formattedMessage)
            .contains("push_delivery_report_failed", "ResourceAccessException")
    }
}
