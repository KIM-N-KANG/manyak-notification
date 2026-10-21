package com.knk.manyak.notification.push.consumer

import java.time.ZoneOffset
import java.time.Clock
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.knk.manyak.notification.push.client.PushDeliveryClient
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
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val client = mock(PushEligibilityClient::class.java)
    private val messaging = mock(FirebaseMessaging::class.java)
    private val store = MemoryStore()
    private val registry = SimpleMeterRegistry()
    private val consumer = NotificationConsumer(NotificationService(client, FcmPushSender(messaging, client, registry, clock = clock), clock), store, registry, mock(PushDeliveryClient::class.java), clock)
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

    @Test fun `각 선점 분기는 소비 지표와 일치하는 INFO 결과를 남긴다`() {
        val logger = LoggerFactory.getLogger(NotificationConsumer::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            listOf(
                Triple(Claim.DONE, ConsumeResult.SUCCESS, null),
                Triple(Claim.BUSY, ConsumeResult.RETRY, null),
                Triple(Claim.ACQUIRED, ConsumeResult.DISCARD, "EXPIRED"),
            ).forEach { (claim, expected, reason) ->
                appender.list.clear()
                store.state = claim
                assertThat(consumer.onMessage(message.copy(expiresAt = Instant.EPOCH))).isEqualTo(expected)
                val logs = appender.list.filter { it.level == Level.INFO }
                assertThat(logs).hasSize(1)
                assertThat(logs.single().formattedMessage).contains("messageId=id", "result=$expected", "reason=$reason")
                assertThat(registry.get(NotificationConsumer.METRIC).tag("outcome", expected.name.lowercase()).counter().count()).isEqualTo(1.0)
            }
            verifyNoInteractions(client, messaging)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    class MemoryStore : DeliveryStore {
        var state = Claim.ACQUIRED
        val sent = mutableSetOf<String>()
        override fun claim(messageId: String, owner: String): Claim = state.also { if (it == Claim.ACQUIRED) state = Claim.BUSY }
        override fun complete(messageId: String, owner: String) { state = Claim.DONE }
        override fun release(messageId: String, owner: String) { state = Claim.ACQUIRED }
        override fun sentCount(messageId: String) = sent.size
        override fun wasSent(messageId: String, token: String) = token in sent
        override fun recordSent(messageId: String, owner: String, token: String) { sent.add(token) }
    }
}

fun <T> anyValue(): T = org.mockito.Mockito.any<T>()
