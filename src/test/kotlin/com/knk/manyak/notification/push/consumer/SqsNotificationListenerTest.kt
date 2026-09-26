package com.knk.manyak.notification.push.consumer

import com.knk.manyak.notification.push.dto.PushKind
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.*
import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

class SqsNotificationListenerTest {
    private val mapper = jacksonObjectMapper()
    private val consumer = mock(NotificationConsumer::class.java)
    private val listener = SqsNotificationListener(mapper, consumer, "https://sqs.ap-northeast-2.amazonaws.com/123456789012/push")
    private val message = PushMessage("id", UUID.randomUUID(), PushKind.SERVICE, "STORY_COMPLETED",
        mapOf("type" to "STORY_COMPLETED"), null, "request", "session", 1)

    @ParameterizedTest
    @EnumSource(value = ConsumeResult::class, names = ["SUCCESS", "DISCARD"])
    fun `완료 결과는 정상 반환하여 ON_SUCCESS 삭제 대상이 된다`(result: ConsumeResult) {
        `when`(consumer.onMessage(message)).thenReturn(result)
        assertThatCode { listener.receive(mapper.writeValueAsString(message)) }.doesNotThrowAnyException()
        verify(consumer).onMessage(message)
    }

    @Test fun `RETRY는 예외로 전달하여 삭제하지 않는다`() {
        `when`(consumer.onMessage(message)).thenReturn(ConsumeResult.RETRY)
        assertThatThrownBy { listener.receive(mapper.writeValueAsString(message)) }
            .isInstanceOf(RetryMessageException::class.java)
    }

    @Test fun `잘못된 JSON은 소비 전에 실패하여 Redrive에 맡긴다`() {
        assertThatThrownBy { listener.receive("{broken") }.isInstanceOf(JacksonException::class.java)
        verifyNoInteractions(consumer)
    }

    @Test fun `검증 실패는 소비 전에 예외로 전달한다`() {
        assertThatThrownBy { listener.receive(mapper.writeValueAsString(message.copy(schemaVersion = 2))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verifyNoInteractions(consumer)
    }
}
