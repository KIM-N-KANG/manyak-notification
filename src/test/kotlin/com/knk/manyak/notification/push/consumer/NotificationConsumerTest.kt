package com.knk.manyak.notification.push.consumer

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.knk.manyak.notification.push.client.PushEligibilityClient
import com.knk.manyak.notification.push.dto.*
import com.knk.manyak.notification.push.service.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Instant
import java.util.UUID

class NotificationConsumerTest {
    private val client = mock(PushEligibilityClient::class.java)
    private val messaging = mock(FirebaseMessaging::class.java)
    private val store = MemoryStore()
    private val registry = SimpleMeterRegistry()
    private val consumer = NotificationConsumer(NotificationService(client, FcmPushSender(messaging, client, registry)), store, registry)
    private val message = PushMessage("id", UUID.randomUUID(), PushKind.SERVICE, "STORY_COMPLETED", mapOf("type" to "STORY_COMPLETED"), null, "request", "session", 1)

    private fun allow() {
        `when`(client.getEligibility(anyValue(), anyValue(), anyValue()))
            .thenReturn(PushEligibilityResponse(true, "OK", listOf(PushEligibilityToken("a", PushPlatform.ANDROID))))
    }

    @Test fun `완료된 메시지는 다시 발송하지 않는다`() {
        allow()
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.SUCCESS)
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.SUCCESS)
        verify(messaging, times(1)).send(any(Message::class.java))
    }

    @Test fun `다른 소비자가 처리 중이면 재시도한다`() {
        store.state = Claim.BUSY
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.RETRY)
        verifyNoInteractions(client, messaging)
    }

    @Test fun `만료 메시지는 완료 기록 후 폐기한다`() {
        assertThat(consumer.onMessage(message.copy(expiresAt = Instant.EPOCH))).isEqualTo(ConsumeResult.DISCARD)
        assertThat(store.state).isEqualTo(Claim.DONE)
        verifyNoInteractions(client, messaging)
    }

    @Test fun `자격 조회 실패는 선점을 풀고 재시도한다`() {
        `when`(client.getEligibility(anyValue(), anyValue(), anyValue())).thenThrow(IllegalStateException())
        assertThat(consumer.onMessage(message)).isEqualTo(ConsumeResult.RETRY)
        assertThat(store.state).isEqualTo(Claim.ACQUIRED)
    }

    class MemoryStore : DeliveryStore {
        var state = Claim.ACQUIRED
        val sent = mutableSetOf<String>()
        override fun claim(messageId: String, owner: String): Claim = state.also { if (it == Claim.ACQUIRED) state = Claim.BUSY }
        override fun complete(messageId: String, owner: String) { state = Claim.DONE }
        override fun release(messageId: String, owner: String) { state = Claim.ACQUIRED }
        override fun wasSent(messageId: String, token: String) = token in sent
        override fun recordSent(messageId: String, owner: String, token: String) { sent.add(token) }
    }
}

fun <T> anyValue(): T = org.mockito.Mockito.any<T>()
