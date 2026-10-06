package com.knk.manyak.notification.push.consumer

import io.awspring.cloud.sqs.support.converter.SqsHeaderMapper
import io.awspring.cloud.sqs.support.observation.SqsListenerObservation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.messaging.support.MessageBuilder
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

class SqsTraceHeaderTest {
    @Test fun `서버 String 메시지 속성을 내장 관측 getter가 그대로 읽는다`() {
        val traceparent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
        val raw = Message.builder().messageId("12345678-1234-1234-1234-123456789abc").body("{}")
            .messageAttributes(mapOf("traceparent" to MessageAttributeValue.builder().dataType("String").stringValue(traceparent).build())).build()
        val message = MessageBuilder.withPayload("{}").copyHeaders(SqsHeaderMapper().toHeaders(raw))
            .setHeader("Sqs_QueueName", "push.requested").build()
        val context = SqsListenerObservation.Context(message)
        assertThat(context.getter.get(requireNotNull(context.carrier), "traceparent")).isEqualTo(traceparent)
    }
}
