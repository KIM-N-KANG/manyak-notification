package com.knk.manyak.notification.push

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.knk.manyak.notification.push.client.PushEligibilityClient
import com.knk.manyak.notification.push.dto.*
import com.knk.manyak.notification.push.service.FcmPushSender
import com.knk.manyak.notification.push.service.NotificationService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class NotificationClockTest {
    private val start = Instant.parse("2030-01-01T14:59:59Z")
    private val clock = mock(Clock::class.java).apply { `when`(instant()).thenReturn(start) }
    private val client = mock(PushEligibilityClient::class.java)
    private val messaging = mock(FirebaseMessaging::class.java)
    private val sender = FcmPushSender(messaging, client, SimpleMeterRegistry(), clock = clock)
    private val service = NotificationService(client, sender, clock)
    private val request = NotificationRequest(UUID.randomUUID(), PushKind.MARKETING, "ATTENDANCE_REMINDER", emptyMap(), start.plusSeconds(1))
    private val tokens = listOf(PushEligibilityToken("first", PushPlatform.ANDROID), PushEligibilityToken("second", PushPlatform.ANDROID))

    private fun allow(afterLookup: () -> Unit = {}) {
        `when`(client.getEligibility(request.recipientId, request.kind, start)).thenAnswer {
            afterLookup()
            PushEligibilityResponse(true, "OK", tokens)
        }
    }

    @Test
    fun `고정 시각이 기한과 같으면 자격 조회와 발송을 생략한다`() {
        val fixedService = NotificationService(client, sender, Clock.fixed(request.expiresAt, ZoneOffset.UTC))
        assertThat(fixedService.send(request).outcome).isEqualTo(NotificationOutcome.EXPIRED)
        verifyNoInteractions(client, messaging)
    }

    @Test
    fun `자격 조회 도중 기한에 도달하면 발송하지 않는다`() {
        allow { `when`(clock.instant()).thenReturn(request.expiresAt) }
        assertThat(service.send(request).outcome).isEqualTo(NotificationOutcome.EXPIRED)
        verify(client).getEligibility(request.recipientId, request.kind, start)
        verifyNoInteractions(messaging)
    }

    @Test
    fun `기기 발송 사이 기한에 도달하면 다음 기기는 실패 집계하고 재시도하지 않는다`() {
        allow()
        `when`(messaging.send(any(Message::class.java))).thenAnswer {
            `when`(clock.instant()).thenReturn(request.expiresAt)
            "sent"
        }
        val response = service.send(request)
        assertThat(response.outcome).isEqualTo(NotificationOutcome.SENT)
        assertThat(response.reason).isEqualTo("PARTIAL_FAILURE")
        assertThat(response.sent).isEqualTo(1)
        assertThat(response.failed).isEqualTo(1)
        assertThat(response.retryable).isZero()
        verify(messaging, times(1)).send(any(Message::class.java))
    }

    @Test
    fun `각 기기 발송 직전 시각으로 Android TTL을 다시 계산한다`() {
        allow()
        val ttls = mutableListOf<String>()
        `when`(messaging.send(any(Message::class.java))).thenAnswer {
            val message = it.getArgument<Message>(0)
            val android = field(message, "androidConfig")!!
            ttls += field(android, "ttl") as String
            `when`(clock.instant()).thenReturn(start.plusMillis(750))
            "sent"
        }
        assertThat(service.send(request).sent).isEqualTo(2)
        assertThat(ttls).containsExactly("1s", "0.250000000s")
    }

    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
}
