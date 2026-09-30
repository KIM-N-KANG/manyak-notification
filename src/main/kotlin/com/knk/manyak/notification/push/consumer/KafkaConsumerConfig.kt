package com.knk.manyak.notification.push.consumer

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

// Boot Kafka 자동 구성을 추가하지 않아 dev/prod에는 Kafka 클라이언트·리스너가 생성되지 않는다.
@Configuration
@Profile("local & !dev & !prod")
@EnableKafka
class KafkaConsumerConfig(
    @Value("\${spring.kafka.bootstrap-servers:localhost:9092}") private val bootstrapServers: String,
) : RetryTopicConfigurationSupport() {
    @Bean
    fun kafkaProducerFactory() = DefaultKafkaProducerFactory<String, ByteArray>(mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
        ProducerConfig.ACKS_CONFIG to "all",
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
    ))

    @Bean
    fun kafkaTemplate() = KafkaTemplate(kafkaProducerFactory())

    @Bean
    fun kafkaListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, ByteArray> =
        ConcurrentKafkaListenerContainerFactory<String, ByteArray>().apply {
            setConsumerFactory(DefaultKafkaConsumerFactory<String, ByteArray>(mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 1,
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to 960_000,
            )))
            containerProperties.ackMode = ContainerProperties.AckMode.RECORD
            containerProperties.pollTimeout = 250
        }

    @Bean
    fun taskScheduler() = ThreadPoolTaskScheduler().apply { poolSize = 1; setThreadNamePrefix("push-retry-") }

    override fun configureCustomizers(customizersConfigurer: CustomizersConfigurer) {
        // 재시도/DLQ 발행 확인 실패는 원본 오프셋 커밋으로 이어지면 안 된다.
        customizersConfigurer.customizeDeadLetterPublishingRecoverer { it.setFailIfSendResultIsError(true) }
    }
}
