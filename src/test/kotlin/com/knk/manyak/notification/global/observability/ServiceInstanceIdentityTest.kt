package com.knk.manyak.notification.global.observability

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.resources.Resource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.UUID

class ServiceInstanceIdentityTest {
    private val runner = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(OpenTelemetrySdkAutoConfiguration::class.java))

    @Test
    fun `기동마다 새 UUID를 리소스에 부여하고 같은 컨텍스트에서는 유지한다`() {
        val ids = mutableListOf<String>()
        repeat(2) {
            runner.run { context ->
                assertThat(context).hasNotFailed().hasSingleBean(Resource::class.java)
                val resource = context.getBean(Resource::class.java)
                val key = AttributeKey.stringKey("service.instance.id")
                val id = resource.getAttribute(key)
                assertThat(id).isNotBlank()
                assertThat(UUID.fromString(id).toString()).isEqualTo(id)
                assertThat(resource.getAttribute(AttributeKey.stringKey("service.name")))
                    .isEqualTo("manyak-notification")
                assertThat(context.getBean(Resource::class.java).getAttribute(key)).isEqualTo(id)
                ids.add(id!!)
            }
        }
        assertThat(ids).doesNotHaveDuplicates()
    }
}
