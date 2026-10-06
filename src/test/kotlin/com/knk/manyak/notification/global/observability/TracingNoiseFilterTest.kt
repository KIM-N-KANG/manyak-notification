package com.knk.manyak.notification.global.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class TracingNoiseFilterTest {
    @Test fun `예약 보안 actuator 루트 Redis만 제외한다`() {
        val predicate = TracingNoiseFilter.predicate()
        for (name in listOf("tasks.scheduled.execution", "spring.security.filterchains", "lettuce")) {
            assertThat(predicate.test(name, Observation.Context())).isFalse()
        }
        assertThat(predicate.test("lettuce", Observation.Context().apply { parentObservation = Observation.NOOP })).isFalse()
        val registry = ObservationRegistry.create().apply { observationConfig().observationHandler { true } }
        val parent = Observation.start("fcm.send", registry)
        try {
            assertThat(predicate.test("lettuce", Observation.Context().apply { parentObservation = parent })).isTrue()
        } finally { parent.stop() }
        for ((path, expected) in listOf("/actuator/health" to false, "/internal/push" to true)) {
            val context = ServerRequestObservationContext(MockHttpServletRequest("GET", path), MockHttpServletResponse())
            assertThat(predicate.test("http.server.requests", context)).isEqualTo(expected)
        }
        assertThat(predicate.test("fcm.send", Observation.Context())).isTrue()
    }
}
