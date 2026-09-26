package com.knk.manyak.notification.push.consumer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class ConsumerTimingPropertiesTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(Config::class.java)

    @Test fun `기본 선점은 2분이고 4분 재시도 창보다 짧다`() {
        runner.run {
            assertThat(it).hasNotFailed()
            val timing = it.getBean(ConsumerTimingProperties::class.java)
            assertThat(timing.processingTtlMs).isEqualTo(120_000)
            assertThat(timing.processingBudgetMs).isEqualTo(10_000)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "processing-ttl-ms=240000", "processing-ttl-ms=900000", "processing-ttl-ms=0",
        "retry-attempts=1", "retry-delay-ms=0", "retry-delay-ms=30000",
        "processing-budget-ms=120000", "processing-budget-ms=0",
    ])
    fun `재시도 창보다 긴 선점과 유효하지 않은 설정은 기동 실패한다`(property: String) {
        runner.withPropertyValues("manyak.push.consumer.$property").run { assertThat(it).hasFailed() }
    }

    @Test fun `축소한 시간도 동일한 부등식을 검증한다`() {
        runner.withPropertyValues("manyak.push.consumer.retry-delay-ms=1000",
            "manyak.push.consumer.processing-ttl-ms=2500", "manyak.push.consumer.processing-budget-ms=100").run {
            assertThat(it).hasNotFailed()
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConsumerTimingProperties::class)
    class Config
}
