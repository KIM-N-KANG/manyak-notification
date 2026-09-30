package com.knk.manyak.notification.push

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.knk.manyak.notification.push.client.PushEligibilityClient
import com.knk.manyak.notification.push.dto.PushEligibilityToken
import com.knk.manyak.notification.push.dto.PushPlatform
import com.knk.manyak.notification.push.service.FcmPushSender
import com.knk.manyak.notification.push.service.PushSendMeters
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.UUID

class FcmMessageTest {
    private val messaging = mock(FirebaseMessaging::class.java)
    private val client = mock(PushEligibilityClient::class.java)
    private val registry = SimpleMeterRegistry()
    private val sender = FcmPushSender(messaging, client, registry)
    private val publicId = UUID.randomUUID()

    @Test
    fun `모든 결과 메트릭은 발송 전에 0으로 등록한다`() {
        PushSendMeters().bindTo(registry)
        FcmPushSender.OUTCOMES.forEach {
            assertThat(registry.get(FcmPushSender.METRIC_PUSH_SEND_RESULT).tag("outcome", it).counter().count()).isZero()
        }
    }

    /** [Message]는 data 접근자가 없어 SDK 내부 필드를 읽는다. 키 이름이 바뀌면 이 헬퍼만 고친다. */
    @Suppress("UNCHECKED_CAST")
    private fun dataOf(message: Message): Map<String, String> =
        Message::class.java.getDeclaredField("data").also { it.isAccessible = true }.get(message) as Map<String, String>

    // SDK가 공개 접근자를 제공하지 않아 기존 data 검증과 같은 방식으로 전송 객체를 확인한다.
    private fun fieldOf(value: Any, name: String): Any? =
        value.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(value)

    @Test
    fun `ANDROID는 HIGH data 전용이고 webpush가 없다`() {
        sender.sendToUser(publicId, listOf(PushEligibilityToken("token", PushPlatform.ANDROID)), mapOf("title" to "제목"))
        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        assertThat(fieldOf(captor.value, "webpushConfig")).isNull()
        assertThat(fieldOf(captor.value, "notification")).isNull()
        assertThat(fieldOf(fieldOf(captor.value, "androidConfig")!!, "priority")).isEqualTo("high")
        assertThat(fieldOf(fieldOf(captor.value, "androidConfig")!!, "ttl")).isNull()
    }

    @Test
    fun `ANDROID는 지정한 NORMAL 우선순위와 TTL을 전달한다`() {

        sender.sendToUser(publicId, listOf(PushEligibilityToken("token", PushPlatform.ANDROID)), mapOf("type" to "ATTENDANCE_REMINDER"), AndroidConfig.Priority.NORMAL, 500L)

        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        val android = fieldOf(captor.value, "androidConfig")!!
        assertThat(fieldOf(android, "priority")).isEqualTo("normal")
        assertThat(fieldOf(android, "ttl")).isEqualTo("0.500000000s")
    }

    @Test
    fun `4주를 넘는 TTL은 FCM 상한으로 깎는다`() {

        sender.sendToUser(publicId, listOf(PushEligibilityToken("token", PushPlatform.ANDROID)), mapOf("type" to "PROMOTION"), AndroidConfig.Priority.NORMAL, FcmPushSender.MAX_TTL_MILLIS + 1000L)

        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        assertThat(fieldOf(fieldOf(captor.value, "androidConfig")!!, "ttl")).isEqualTo("2419200s")
    }

    @Test
    fun `프로모션의 NORMAL 우선순위는 TTL을 설정하지 않는다`() {

        sender.sendToUser(publicId, listOf(PushEligibilityToken("token", PushPlatform.ANDROID)), mapOf("type" to "PROMOTION"), AndroidConfig.Priority.NORMAL)

        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        val android = fieldOf(captor.value, "androidConfig")!!
        assertThat(fieldOf(android, "priority")).isEqualTo("normal")
        assertThat(fieldOf(android, "ttl")).isNull()
    }

    @Test
    fun `WEB은 광고 문구와 수신자 및 딥링크를 그대로 보낸다`() {
        val data = mapOf("title" to "(광고) 선물", "body" to "출석하세요", "deepLink" to "https://manyak.app/shop")
        sender.sendToUser(publicId, listOf(PushEligibilityToken("token", PushPlatform.WEB)), data)
        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        val message = captor.value
        assertThat(dataOf(message)).containsAllEntriesOf(data).containsEntry("recipientId", publicId.toString())
        assertThat(fieldOf(message, "androidConfig")).isNull()
        val webpush = fieldOf(message, "webpushConfig")!!
        val notification = fieldOf(webpush, "notification") as Map<*, *>
        assertThat(notification["title"]).isEqualTo("(광고) 선물")
        assertThat(notification["body"]).isEqualTo("출석하세요")
        assertThat(notification["icon"]).isEqualTo("https://manyak.app/icons/icon-192.png")
        assertThat(fieldOf(fieldOf(webpush, "fcmOptions")!!, "link")).isEqualTo(data["deepLink"])
    }

    @Test
    fun `WEB 스토리 완성은 본문과 딥링크 없이도 홈 링크로 보낸다`() {
        sender.sendToUser(publicId, listOf(PushEligibilityToken("token", PushPlatform.WEB)), mapOf("type" to "STORY_COMPLETED", "title" to "스토리"))
        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        val webpush = fieldOf(captor.value, "webpushConfig")!!
        assertThat(fieldOf(fieldOf(webpush, "fcmOptions")!!, "link")).isEqualTo("https://manyak.app")
        assertThat((fieldOf(webpush, "notification") as Map<*, *>)["title"]).isEqualTo("스토리")
    }

}
