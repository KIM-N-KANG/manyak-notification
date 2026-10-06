package com.knk.manyak.notification.push.config

import io.micrometer.observation.ObservationRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class PushClientConfig {
    @Bean
    fun serverRestClientBuilder(registry: ObservationRegistry): RestClient.Builder = RestClient.builder().observationRegistry(registry).requestFactory(
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(1))
            setReadTimeout(Duration.ofSeconds(2))
        },
    )
}
