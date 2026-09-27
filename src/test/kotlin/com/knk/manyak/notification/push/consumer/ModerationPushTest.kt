package com.knk.manyak.notification.push.consumer

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
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

    @Test fun `검수 완료는 SERVICE로 검증을 통과한다`() {
        assertThatCode { message().validate() }.doesNotThrowAnyException()
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
    @CsvSource("STORY_COMPLETED,SERVICE", "ATTENDANCE_REMINDER,MARKETING", "PROMOTION,MARKETING")
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
        val consumer = NotificationConsumer(NotificationService(client, FcmPushSender(messaging, client, registry)), store, registry)
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
