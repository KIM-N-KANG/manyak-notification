package com.knk.manyak.notification.push.consumer

import java.time.ZoneOffset
import java.time.Clock
import java.time.Instant
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.knk.manyak.notification.push.client.PushDeliveryClient
import com.knk.manyak.notification.push.client.PushEligibilityClient
import com.knk.manyak.notification.push.dto.*
import com.knk.manyak.notification.push.service.FcmPushSender
import com.knk.manyak.notification.push.service.NotificationService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

class ModerationPushTest {
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val recipientId = UUID.randomUUID()

    // 서버 ModerationPushListeners.pushData와 동일한 계약. storyId 없는 CREATE와 있는 UPDATE 모두 검증한다.
    private fun message(status: String = "APPROVED", hasStory: Boolean = true): PushMessage {
        val data = buildMap {
            put("type", "STORY_MODERATION_COMPLETED")
            put("submissionId", "submission-id")
            put("status", status)
            if (hasStory) put("storyId", "story-id")
            put("deepLink", if (hasStory) "https://manyak.app/stories/story-id/edit"
                else "https://manyak.app/studio/story/general?submissionId=submission-id")
        }
        return PushMessage("story-moderation:submission-id:2", recipientId, PushKind.SERVICE,
            "STORY_MODERATION_COMPLETED", data, null, "request", "session", 1)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource("NOTICE,SERVICE,noticeId,notices,high", "PROMOTION,MARKETING,noticeId,notices,normal", "PROMOTION,MARKETING,storyId,stories,normal")
    fun `공지와 스토리 캠페인 데이터 및 플랫폼별 딥링크를 보존한다`(
        type: String, kind: PushKind, targetKey: String, path: String, priority: String,
    ) {
        val recipient = UUID.randomUUID()
        val target = UUID.randomUUID().toString()
        val data = mapOf("type" to type, "campaignId" to UUID.randomUUID().toString(), targetKey to target,
            "title" to if (kind == PushKind.SERVICE) "공지" else "(광고) 소식", "body" to "본문",
            "deepLink" to "https://manyak.app/$path/$target")
        val message = PushMessage("campaign-message", recipient, kind, type, data, null, "unknown", "unknown", 1)
        message.validate()
        val client = mock(PushEligibilityClient::class.java)
        val messaging = mock(FirebaseMessaging::class.java)
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
        `when`(client.getEligibility(anyValue(), anyValue(), anyValue())).thenReturn(PushEligibilityResponse(true, "OK",
            listOf(PushEligibilityToken("android", PushPlatform.ANDROID), PushEligibilityToken("web", PushPlatform.WEB))))
        NotificationService(client, FcmPushSender(messaging, client, registry, clock = clock), clock).send(message.request())
        verify(client).getEligibility(recipient, kind, clock.instant())
        val captor = org.mockito.ArgumentCaptor.forClass(Message::class.java)
        verify(messaging, times(2)).send(captor.capture())
        fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(value)
        captor.allValues.forEach {
            @Suppress("UNCHECKED_CAST")
            val payload = field(it, "data") as Map<String, String>
            assertThat(payload).containsAllEntriesOf(data).containsEntry("recipientId", recipient.toString())
        }
        val android = captor.allValues.first()
        assertThat(field(android, "notification")).isNull()
        assertThat(field(field(android, "androidConfig")!!, "priority")).isEqualTo(priority)
        val web = field(captor.allValues.last(), "webpushConfig")!!
        assertThat(field(field(web, "fcmOptions")!!, "link")).isEqualTo(data["deepLink"])
        val notification = field(web, "notification") as Map<*, *>
        assertThat(notification["title"]).isEqualTo(data["title"])
        assertThat(notification["body"]).isEqualTo(data["body"])
    }

    @Test fun `정보성 공지는 SERVICE만 허용한다`() {
        val message = PushMessage("notice:id:member", UUID.randomUUID(), PushKind.SERVICE, "NOTICE",
            mapOf("type" to "NOTICE"), null, "unknown", "unknown", 1)
        message.validate()
        org.assertj.core.api.Assertions.assertThatThrownBy { message.copy(kind = PushKind.MARKETING).validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `검수 완료는 SERVICE로 검증을 통과한다`() {
        assertThatCode { message().validate() }.doesNotThrowAnyException()
    }

    @Test fun `공개 중단은 SERVICE로 허용하고 MARKETING과 불일치하는 data type은 거부한다`() {
        val takenDown = message().copy(
            messageId = "story-taken-down:story-id:1893456000000",
            type = "STORY_TAKEN_DOWN",
            data = mapOf("type" to "STORY_TAKEN_DOWN", "storyId" to "story-id",
                "title" to "스토리 공개가 제한됐어요", "body" to "공개 중단 안내",
                "deepLink" to "https://manyak.app/studio?utm_source=push&utm_medium=push&utm_campaign=story_taken_down&utm_content=story_taken_down"),
        )
        assertThatCode { takenDown.validate() }.doesNotThrowAnyException()
        assertThatThrownBy { takenDown.copy(kind = PushKind.MARKETING).validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { takenDown.copy(data = takenDown.data + ("type" to "STORY_COMPLETED")).validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `검수 완료를 MARKETING으로 보내면 거부한다`() {
        assertThatThrownBy { message().copy(kind = PushKind.MARKETING).validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `검수 완료의 data type이 다르면 거부한다`() {
        assertThatThrownBy { message().copy(data = message().data + ("type" to "STORY_COMPLETED")).validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @CsvSource("CREDIT_EXPIRY_REMINDER,SERVICE", "STORY_COMPLETED,SERVICE", "ATTENDANCE_REMINDER,MARKETING", "PROMOTION,MARKETING")
    fun `기존 타입의 허용 kind와 반대 kind 거부를 유지한다`(type: String, kind: PushKind) {
        val existing = message().copy(type = type, kind = kind, data = mapOf("type" to type))
        assertThatCode { existing.validate() }.doesNotThrowAnyException()
        val wrongKind = if (kind == PushKind.SERVICE) PushKind.MARKETING else PushKind.SERVICE
        assertThatThrownBy { existing.copy(kind = wrongKind).validate() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @CsvSource("APPROVED,true", "APPROVED,false", "REJECTED,true", "REJECTED,false", "FAILED,true", "FAILED,false")
    fun `SQS 검수 알림은 SERVICE 자격 조회 후 서버 local과 같은 Android와 웹 메시지를 보낸다`(status: String, hasStory: Boolean) {
        val messaging = mock(FirebaseMessaging::class.java)
        val client = mock(PushEligibilityClient::class.java)
        val registry = SimpleMeterRegistry()
        val store = NotificationConsumerTest.MemoryStore()
        val consumer = NotificationConsumer(NotificationService(client, FcmPushSender(messaging, client, registry, clock = clock), clock), store, registry, mock(PushDeliveryClient::class.java), clock)
        val mapper = jacksonObjectMapper()
        val listener = SqsNotificationListener(mapper, consumer, "https://sqs.ap-northeast-2.amazonaws.com/123456789012/push")
        val message = message(status, hasStory)
        `when`(client.getEligibility(anyValue(), anyValue(), anyValue())).thenReturn(
            PushEligibilityResponse(true, "OK", listOf(PushEligibilityToken("android", PushPlatform.ANDROID),
                PushEligibilityToken("web", PushPlatform.WEB))))

        listener.receive(mapper.writeValueAsString(message))

        val eligibilityCall = mockingDetails(client).invocations.single { it.method.name == "getEligibility" }
        assertThat(eligibilityCall.arguments[0]).isEqualTo(recipientId)
        assertThat(eligibilityCall.arguments[1]).isEqualTo(PushKind.SERVICE)
        assertThat(store.state).isEqualTo(Claim.DONE)
        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging, times(2)).send(captor.capture())
        val androidMessage = captor.allValues[0]
        val webMessage = captor.allValues[1]
        for (sent in captor.allValues) {
            assertThat(field(sent, "data")).isEqualTo(message.data + ("recipientId" to recipientId.toString()))
            assertThat(field(sent, "notification")).isNull()
        }
        assertThat(field(androidMessage, "webpushConfig")).isNull()
        val android = field(androidMessage, "androidConfig")!!
        assertThat(field(android, "priority")).isEqualTo("high")
        assertThat(field(android, "ttl")).isNull()
        assertThat(field(android, "notification")).isNull()
        assertThat(field(webMessage, "androidConfig")).isNull()
        val web = field(webMessage, "webpushConfig")!!
        val notification = field(web, "notification") as Map<*, *>
        // 서버 local은 검수 data에 title/body를 넣지 않으므로 알림 서비스도 새 문구를 만들지 않는다.
        assertThat(notification["title"]).isNull()
        assertThat(notification["body"]).isNull()
        assertThat(notification["icon"]).isEqualTo("https://manyak.app/icons/icon-192.png")
        assertThat(field(field(web, "fcmOptions")!!, "link")).isEqualTo(message.data["deepLink"])
    }

    private fun field(value: Any, name: String): Any? =
        value.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(value)
}
