package com.knk.manyak.notification.push.consumer

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.BackOff
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
@Profile("local & !dev & !prod")
class KafkaNotificationListener(
    private val mapper: ObjectMapper,
    private val consumer: NotificationConsumer,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @RetryableTopic(
        attempts = "\${manyak.push.consumer.retry-attempts:5}",
        backOff = BackOff(delayString = "\${manyak.push.consumer.retry-delay-ms:60000}"),
        retryTopicSuffix = ".retry", dltTopicSuffix = ".dlq",
        sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
        autoCreateTopics = "false", kafkaTemplate = "kafkaTemplate",
        include = [RetryMessageException::class],
    )
    @KafkaListener(topics = ["push.requested"], groupId = "notification")
    fun receive(payload: ByteArray) {
        // 원본 바이트를 소비/재발행해 JSON 오류도 내용 손실 없이 곧장 DLQ에 남긴다.
        val message = mapper.readValue(payload, PushMessage::class.java).also { it.validate() }
        if (consumer.onMessage(message) == ConsumeResult.RETRY) throw RetryMessageException()
    }

    @DltHandler
    fun deadLetter(payload: ByteArray) {
        registry.counter(NotificationConsumer.METRIC, "outcome", "dlq").increment()
        val message = runCatching { mapper.readValue(payload, PushMessage::class.java) }.getOrNull()
        if (message == null) log.warn("알림 DLQ 수신 (reason=INVALID_MESSAGE)")
        else NotificationConsumer.withCorrelation(message) { log.warn("알림 DLQ 수신 (messageId={})", message.messageId) }
    }
}

class RetryMessageException : RuntimeException("Notification requires broker redelivery")
