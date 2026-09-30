package com.knk.manyak.notification.push.consumer

import io.awspring.cloud.sqs.annotation.SqsListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
@Profile("dev | prod")
class SqsNotificationListener(
    private val mapper: ObjectMapper,
    private val consumer: NotificationConsumer,
    @Value("\${manyak.push.queue-url}") queueUrl: String,
) {
    init {
        require(queueUrl.isNotBlank()) { "manyak.push.queue-url must not be blank in dev/prod" }
    }

    @SqsListener(
        queueNames = ["\${manyak.push.queue-url}"],
        acknowledgementMode = "ON_SUCCESS",
        pollTimeoutSeconds = "20",
        maxConcurrentMessages = "2",
        maxMessagesPerPoll = "2",
    )
    fun receive(payload: String) {
        // 파싱·검증 실패도 예외로 전파한다. 삭제하거나 직접 DLQ로 보내지 않고 큐의 Redrive에 맡긴다.
        val message = mapper.readValue(payload, PushMessage::class.java).also { it.validate() }
        // 상관 ID와 outcome 카운터는 Kafka와 같은 소비자가 기록한다.
        if (consumer.onMessage(message) == ConsumeResult.RETRY) throw RetryMessageException()
    }
}
