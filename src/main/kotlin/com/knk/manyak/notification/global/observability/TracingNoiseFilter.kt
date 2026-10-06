package com.knk.manyak.notification.global.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationPredicate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.observation.ServerRequestObservationContext

@Configuration(proxyBeanMethods = false)
class TracingNoiseFilter {
    @Bean fun tracingNoisePredicate(): ObservationPredicate = predicate()

    companion object {
        fun predicate() = ObservationPredicate { name, context ->
            when {
                name == "tasks.scheduled.execution" -> false
                name.startsWith("spring.security.") -> false
                name == "http.server.requests" && context is ServerRequestObservationContext ->
                    context.carrier?.requestURI?.startsWith("/actuator") != true
                name == "lettuce" && context.parentObservation.let { it == null || (it is Observation && it.isNoop) } -> false
                else -> true
            }
        }
    }
}
