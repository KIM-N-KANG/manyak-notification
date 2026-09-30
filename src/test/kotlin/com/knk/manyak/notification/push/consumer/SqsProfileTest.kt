package com.knk.manyak.notification.push.consumer

import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration
import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry
import io.awspring.cloud.sqs.listener.SqsMessageListenerContainer
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import com.knk.manyak.notification.push.dto.PushKind
import software.amazon.awssdk.services.sqs.model.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

class SqsProfileTest {
    private val sqs = mock(SqsAsyncClient::class.java)
    private val runner = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(SqsAutoConfiguration::class.java))
        .withUserConfiguration(SqsNotificationListener::class.java, KafkaNotificationListener::class.java)
        .withBean(ObjectMapper::class.java, { jacksonObjectMapper() })
        .withBean(NotificationConsumer::class.java, { mock(NotificationConsumer::class.java) })
        .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })

    @ParameterizedTest
    @ValueSource(strings = ["dev", "prod", "local,dev"])
    fun `dev와 prod는 SQS 컨테이너만 등록한다`(profile: String) {
        runner.withBean(SqsAsyncClient::class.java, { sqs })
            .withPropertyValues("spring.profiles.active=$profile",
                "manyak.push.queue-url=https://sqs.ap-northeast-2.amazonaws.com/123456789012/push",
                "spring.cloud.aws.sqs.listener.auto-startup=false")
            .run { context ->
                assertThat(context).hasNotFailed().hasSingleBean(SqsNotificationListener::class.java)
                    .doesNotHaveBean(KafkaNotificationListener::class.java)
                val containers = context.getBean(MessageListenerContainerRegistry::class.java).listenerContainers
                assertThat(containers).hasSize(1)
                val options = (containers.single() as SqsMessageListenerContainer<*>).containerOptions
                assertThat(options.acknowledgementMode).isEqualTo(AcknowledgementMode.ON_SUCCESS)
                assertThat(options.pollTimeout).isEqualTo(Duration.ofSeconds(20))
                assertThat(options.maxConcurrentMessages).isEqualTo(2)
                assertThat(options.maxMessagesPerPoll).isEqualTo(2)
                assertThat(options.messageVisibility).isNull()
                verifyNoInteractions(sqs)
            }
    }

    @Test fun `라이브러리는 성공과 폐기만 삭제하고 실패의 가시성은 변경하지 않는다`() {
        val mapper = jacksonObjectMapper()
        val base = PushMessage("id", UUID.randomUUID(), PushKind.SERVICE, "STORY_COMPLETED",
            mapOf("type" to "STORY_COMPLETED"), null, "request", "session", 1)
        val bodies = listOf(
            mapper.writeValueAsString(base.copy(messageId = "success")),
            mapper.writeValueAsString(base.copy(messageId = "discard")),
            mapper.writeValueAsString(base.copy(messageId = "retry")),
            "{broken",
            mapper.writeValueAsString(base.copy(schemaVersion = 2)),
        )
        val pending = ConcurrentLinkedQueue(bodies.mapIndexed { index, body ->
            Message.builder().messageId(UUID.randomUUID().toString()).receiptHandle("receipt-$index").body(body).build()
        })
        val deleted = ConcurrentLinkedQueue<String>()
        `when`(sqs.receiveMessage(any(ReceiveMessageRequest::class.java))).thenAnswer {
            val request = it.getArgument<ReceiveMessageRequest>(0)
            assertThat(request.waitTimeSeconds()).isEqualTo(20)
            assertThat(request.maxNumberOfMessages()).isLessThanOrEqualTo(2)
            assertThat(request.visibilityTimeout() == null).isTrue()
            val message = pending.poll()
            if (message == null) Thread.sleep(20)
            CompletableFuture.completedFuture(ReceiveMessageResponse.builder()
                .messages(if (message == null) emptyList() else listOf(message)).build())
        }
        `when`(sqs.deleteMessageBatch(any(DeleteMessageBatchRequest::class.java))).thenAnswer {
            val request = it.getArgument<DeleteMessageBatchRequest>(0)
            deleted.addAll(request.entries().map { entry -> entry.receiptHandle() })
            CompletableFuture.completedFuture(DeleteMessageBatchResponse.builder()
                .successful(request.entries().map { entry -> DeleteMessageBatchResultEntry.builder().id(entry.id()).build() }).build())
        }
        runner.withBean(SqsAsyncClient::class.java, { sqs })
            .withPropertyValues("spring.profiles.active=dev",
                "manyak.push.queue-url=https://sqs.ap-northeast-2.amazonaws.com/123456789012/push",
                "spring.cloud.aws.sqs.listener.auto-startup=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                val consumer = context.getBean(NotificationConsumer::class.java)
                `when`(consumer.onMessage(anyValue())).thenAnswer {
                    when (it.getArgument<PushMessage>(0).messageId) {
                        "success" -> ConsumeResult.SUCCESS
                        "discard" -> ConsumeResult.DISCARD
                        else -> ConsumeResult.RETRY
                    }
                }
                val container = context.getBean(MessageListenerContainerRegistry::class.java).listenerContainers.single()
                try {
                    container.start()
                    await().atMost(Duration.ofSeconds(10)).untilAsserted {
                        assertThat(pending).isEmpty()
                        assertThat(deleted).containsExactlyInAnyOrder("receipt-0", "receipt-1")
                        verify(consumer, times(3)).onMessage(anyValue())
                    }
                } finally {
                    container.stop()
                }
                assertThat(deleted).containsExactlyInAnyOrder("receipt-0", "receipt-1")
                assertThat(mockingDetails(sqs).invocations.map { it.method.name })
                    .doesNotContain("changeMessageVisibility", "changeMessageVisibilityBatch", "sendMessage", "sendMessageBatch")
            }
    }

    @Test fun `local은 Kafka만 등록하고 SQS 자동 구성을 끈다`() {
        runner.withPropertyValues("spring.profiles.active=local").run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(KafkaNotificationListener::class.java)
                .doesNotHaveBean(SqsNotificationListener::class.java)
                .doesNotHaveBean(SqsAsyncClient::class.java)
                .doesNotHaveBean(MessageListenerContainerRegistry::class.java)
        }
    }

    @Test fun `기본 테스트 프로파일에서도 SQS 자동 구성을 끈다`() {
        runner.run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(SqsAsyncClient::class.java)
                .doesNotHaveBean(MessageListenerContainerRegistry::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `dev에서 큐 URL이 비어 있으면 기동 실패한다`(url: String) {
        runner.withPropertyValues("spring.profiles.active=dev", "manyak.push.queue-url=$url",
            "spring.cloud.aws.sqs.enabled=false").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseMessage("manyak.push.queue-url must not be blank in dev/prod")
        }
    }
}
