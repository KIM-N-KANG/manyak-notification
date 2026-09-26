package com.knk.manyak.notification.push.consumer

import com.google.firebase.IncomingHttpResponse
import com.google.firebase.messaging.*
import com.knk.manyak.notification.global.observability.CorrelationHeaders
import com.knk.manyak.notification.push.client.PushEligibilityClient
import com.knk.manyak.notification.push.dto.*
import com.knk.manyak.notification.push.service.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.slf4j.MDC
import java.time.Instant
import java.util.UUID

class ConsumerOutcomeTest {
    private val client = mock(PushEligibilityClient::class.java)
    private val messaging = mock(FirebaseMessaging::class.java)
    private val store = NotificationConsumerTest.MemoryStore()
    private val registry = SimpleMeterRegistry()
    private val consumer = NotificationConsumer(NotificationService(client, FcmPushSender(messaging, client, registry)), store, registry)
    private val message = PushMessage("id", UUID.randomUUID(), PushKind.SERVICE, "STORY_COMPLETED", mapOf("type" to "STORY_COMPLETED"), null, "request", "session", 1)
    private fun allow(tokens: List<String> = listOf("a")) {
        `when`(client.getEligibility(anyValue(), anyValue(), anyValue())).thenReturn(PushEligibilityResponse(true, "OK", tokens.map { PushEligibilityToken(it, PushPlatform.ANDROID) }))
    }
    private fun failure(code: MessagingErrorCode?, status: Int): FirebaseMessagingException {
        val ex = mock(FirebaseMessagingException::class.java)
        `when`(ex.messagingErrorCode).thenReturn(code)
        val response = mock(IncomingHttpResponse::class.java)
        `when`(response.statusCode).thenReturn(status)
        `when`(ex.httpResponse).thenReturn(response)
        return ex
    }
    @AfterEach fun clear() { MDC.clear() }

    @ParameterizedTest @ValueSource(ints = [400, 401, 403, 404])
    fun `영구 HTTP 오류는 폐기하고 토큰을 지우지 않는다`(status: Int) {
        allow()
        val error = failure(null, status)
        `when`(messaging.send(any(Message::class.java))).thenThrow(error)
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.DISCARD)
        verify(client, never()).deleteInvalidToken(anyValue())
        assertThat(store.state).isEqualTo(Claim.DONE)
    }
    @ParameterizedTest @ValueSource(ints = [429, 500, 503])
    fun `SDK 최종 일시 실패는 재시도한다`(status: Int) {
        allow()
        val error = failure(MessagingErrorCode.UNAVAILABLE, status)
        `when`(messaging.send(any(Message::class.java))).thenThrow(error)
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.RETRY)
        assertThat(store.state).isEqualTo(Claim.ACQUIRED)
    }
    @Test fun `INVALID_ARGUMENT와 잘못된 메시지 조립은 폐기한다`() {
        allow()
        val error = failure(MessagingErrorCode.INVALID_ARGUMENT, 500)
        `when`(messaging.send(any(Message::class.java))).thenThrow(error)
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.DISCARD)
        verify(client, never()).deleteInvalidToken(anyValue())
        store.state = Claim.ACQUIRED
        doThrow(IllegalArgumentException()).`when`(messaging).send(any(Message::class.java))
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.DISCARD)
    }
    @Test fun `UNREGISTERED 정리 실패도 다음 기기를 발송하고 재시도하지 않는다`() {
        allow(listOf("a", "b"))
        val error = failure(MessagingErrorCode.UNREGISTERED, 404)
        `when`(messaging.send(any(Message::class.java))).thenThrow(error).thenReturn("sent")
        doThrow(IllegalStateException()).`when`(client).deleteInvalidToken("a")
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.SUCCESS)
        verify(client).deleteInvalidToken("a")
        verify(messaging, times(2)).send(any(Message::class.java))
    }
    @Test fun `부분 성공 뒤 재처리는 성공 기기를 제외한다`() {
        allow(listOf("a", "b"))
        val error = failure(MessagingErrorCode.QUOTA_EXCEEDED, 429)
        `when`(messaging.send(any(Message::class.java))).thenReturn("sent").thenThrow(error).thenReturn("sent")
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.RETRY)
        assertThat(store.sent).containsExactly("a")
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.SUCCESS)
        assertThat(store.sent).containsExactlyInAnyOrder("a", "b")
        verify(messaging, times(3)).send(any(Message::class.java))
        verify(client, times(2)).getEligibility(anyValue(), anyValue(), anyValue())
    }
    @Test fun `거절 토큰 없음 FCM 비활성은 폐기한다`() {
        `when`(client.getEligibility(anyValue(), anyValue(), anyValue())).thenReturn(PushEligibilityResponse(false, "DENIED", emptyList()))
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.DISCARD)
        store.state = Claim.ACQUIRED
        allow(emptyList())
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.DISCARD)
        store.state = Claim.ACQUIRED
        allow()
        val disabled = NotificationConsumer(NotificationService(client, FcmPushSender(null, client, registry)), store, registry)
        assertThat(disabled.onMessage(message)).isEqualTo(ConsumeResult.DISCARD)
        verifyNoInteractions(messaging)
    }
    @Test fun `메시지 상관 ID와 발송 직전 at을 전달하고 기존 MDC를 복구한다`() {
        MDC.put("request_id", "previous")
        MDC.put("device_id_hash", "unrelated")
        val before = Instant.now()
        `when`(client.getEligibility(anyValue(), anyValue(), anyValue())).thenAnswer {
            assertThat(it.getArgument<Instant>(2)).isBetween(before, Instant.now())
            assertThat(CorrelationHeaders.forwardingHeadersFromMdc()).containsExactlyInAnyOrderEntriesOf(mapOf("X-Manyak-Request-Id" to "request", "X-Manyak-Session-Id" to "session"))
            PushEligibilityResponse(false, "DENIED", emptyList())
        }
        consumer.onMessage(message)
        assertThat(MDC.get("request_id")).isEqualTo("previous")
        assertThat(MDC.get("device_id_hash")).isEqualTo("unrelated")
    }
    @Test fun `SDK 런타임 실패는 재시도한다`() {
        allow()
        `when`(messaging.send(any(Message::class.java))).thenThrow(IllegalStateException("SDK unavailable"))
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.RETRY)
    }
    @Test fun `성공 기록 실패는 다음 기기 발송을 멈추고 재시도한다`() {
        allow(listOf("a", "b"))
        val failingStore = object : DeliveryStore by store {
            override fun recordSent(messageId: String, owner: String, token: String) { throw IllegalStateException("Redis unavailable") }
        }
        val target = NotificationConsumer(NotificationService(client, FcmPushSender(messaging, client, registry)), failingStore, registry)
        assertThat(target.onMessage(message)).isEqualTo(ConsumeResult.RETRY)
        verify(messaging, times(1)).send(any(Message::class.java))
        assertThat(store.state).isEqualTo(Claim.ACQUIRED)
    }
    @Test fun `이미 발송한 기기 조회에도 처리 시간 제한을 적용한다`() {
        val sender = FcmPushSender(messaging, client, registry)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            sender.sendToUser(message.recipientId, listOf(PushEligibilityToken("a", PushPlatform.ANDROID)), message.data,
                alreadySent = { true }, beforeSend = { throw IllegalStateException("budget exhausted") })
        }.isInstanceOf(IllegalStateException::class.java)
        verifyNoInteractions(messaging)
    }
    @Test fun `소비 결과 카운터는 기동 시 0으로 등록한다`() {
        PushSendMeters().bindTo(registry)
        listOf("success", "retry", "discard", "dlq").forEach {
            assertThat(registry.get(NotificationConsumer.METRIC).tag("outcome", it).counter().count()).isZero()
        }
    }
}
