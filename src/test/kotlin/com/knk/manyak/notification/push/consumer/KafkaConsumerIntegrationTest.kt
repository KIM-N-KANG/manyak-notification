package com.knk.manyak.notification.push.consumer

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.knk.manyak.notification.push.client.PushEligibilityClient
import com.knk.manyak.notification.push.dto.*
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@ActiveProfiles("local")
class KafkaConsumerIntegrationTest {
    companion object {
        val kafka = KafkaContainer("apache/kafka:4.3.1").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false").apply { start() }
        val redis = GenericContainer("redis:7-alpine").withExposedPorts(6379).apply { start() }
        init {
            AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use {
                it.createTopics(listOf("push.requested", "push.requested.retry", "push.requested.dlq").map { name -> NewTopic(name, 1, 1) }).all().get()
            }
        }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("manyak.push.consumer.retry-delay-ms") { "1000" }
            registry.add("manyak.push.consumer.processing-ttl-ms") { "2500" }
            registry.add("manyak.push.consumer.processing-budget-ms") { "100" }
        }
    }
    @MockitoBean lateinit var client: PushEligibilityClient
    @MockitoBean lateinit var messaging: FirebaseMessaging
    @Autowired lateinit var template: KafkaTemplate<String, ByteArray>
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var meters: MeterRegistry
    @Autowired lateinit var deliveryStore: RedisDeliveryStore
    @Autowired lateinit var redisTemplate: StringRedisTemplate

    @Test fun `중복 거절 재시도 비차단과 5회 뒤 DLQ 및 잘못된 JSON을 검증한다`() {
        val attempts = ConcurrentHashMap<UUID, AtomicInteger>()
        val retry = message()
        val success = message()
        val denied = message()
        `when`(client.getEligibility(anyValue(), anyValue(), anyValue())).thenAnswer {
            val id = it.getArgument<UUID>(0)
            attempts.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
            when (id) {
                retry.recipientId -> throw IllegalStateException("offline")
                denied.recipientId -> PushEligibilityResponse(false, "DENIED", emptyList())
                else -> PushEligibilityResponse(true, "OK", listOf(PushEligibilityToken("token-$id", PushPlatform.ANDROID)))
            }
        }
        KafkaConsumer<String, ByteArray>(mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "assert-dlq-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        )).use { dlq ->
            dlq.subscribe(listOf("push.requested.dlq"))
            publish(retry)
            await().atMost(Duration.ofSeconds(30)).until { attempts[retry.recipientId]?.get() == 1 }
            publish(success); publish(success); publish(denied)
            await().atMost(Duration.ofSeconds(3)).untilAsserted { verify(messaging, times(1)).send(any(Message::class.java)) }
            assertThat(attempts[retry.recipientId]!!.get()).isLessThan(5)
            template.send("push.requested", "invalid", "{broken".toByteArray()).get()
            val records = mutableListOf<ByteArray>()
            await().atMost(Duration.ofSeconds(45)).until {
                dlq.poll(Duration.ofMillis(200)).forEach { records.add(it.value()) }
                records.size >= 2
            }
            assertThat(records.map { String(it) }).contains("{broken", mapper.writeValueAsString(retry))
            assertThat(attempts[retry.recipientId]!!.get()).isEqualTo(5)
            assertThat(attempts[denied.recipientId]!!.get()).isEqualTo(1)
            verify(messaging, times(1)).send(any(Message::class.java))
            await().atMost(Duration.ofSeconds(10)).untilAsserted {
                assertThat(meters.get(NotificationConsumer.METRIC).tag("outcome", "dlq").counter().count()).isEqualTo(2.0)
            }
        }
    }
    @Test fun `소비자가 선점 직후 죽어도 만료 뒤 재시도에서 DLQ 전에 완료한다`() {
        val message = message()
        `when`(client.getEligibility(anyValue(), anyValue(), anyValue())).thenReturn(
            PushEligibilityResponse(true, "OK", listOf(PushEligibilityToken("recovered", PushPlatform.ANDROID))))
        val retriesBefore = meters.get(NotificationConsumer.METRIC).tag("outcome", "retry").counter().count()
        val dlqBefore = meters.get(NotificationConsumer.METRIC).tag("outcome", "dlq").counter().count()
        // 선점만 남긴 채 죽은 소비자: release/complete는 호출하지 않는다.
        assertThat(deliveryStore.claim(message.messageId, "dead-worker")).isEqualTo(Claim.ACQUIRED)
        publish(message)
        await().atMost(Duration.ofSeconds(2)).untilAsserted {
            assertThat(meters.get(NotificationConsumer.METRIC).tag("outcome", "retry").counter().count()).isGreaterThan(retriesBefore)
        }
        verifyNoInteractions(messaging, client)
        await().atMost(Duration.ofSeconds(8)).untilAsserted {
            assertThat(redisTemplate.opsForValue().get(RedisDeliveryStore.processedKey(message.messageId))).isEqualTo("done")
        }
        verify(messaging, times(1)).send(any(Message::class.java))
        assertThat(meters.get(NotificationConsumer.METRIC).tag("outcome", "retry").counter().count() - retriesBefore).isBetween(1.0, 4.0)
        assertThat(meters.get(NotificationConsumer.METRIC).tag("outcome", "dlq").counter().count()).isEqualTo(dlqBefore)
    }

    private fun message() = PushMessage(UUID.randomUUID().toString(), UUID.randomUUID(), PushKind.SERVICE, "STORY_COMPLETED", mapOf("type" to "STORY_COMPLETED"), null, "request", "session", 1)
    private fun publish(message: PushMessage) { template.send("push.requested", message.recipientId.toString(), mapper.writeValueAsBytes(message)).get() }
}
