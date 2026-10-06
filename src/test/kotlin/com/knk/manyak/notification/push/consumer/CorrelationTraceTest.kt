package com.knk.manyak.notification.push.consumer

import com.knk.manyak.notification.push.dto.PushKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.UUID

class CorrelationTraceTest {
    @Test fun `소비 상관정보는 추적 키를 유지하고 나머지 MDC는 격리한 뒤 복원한다`() {
        val previous = MDC.getCopyOfContextMap()
        try {
            MDC.setContextMap(mapOf("traceId" to "trace", "spanId" to "span", "request_id" to "old", "unrelated" to "old"))
            val message = PushMessage(UUID.randomUUID().toString(), UUID.randomUUID(), PushKind.SERVICE,
                "STORY_COMPLETED", mapOf("type" to "STORY_COMPLETED"), null, "request", "session", 1)
            NotificationConsumer.withCorrelation(message) {
                assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(mapOf(
                    "traceId" to "trace", "spanId" to "span", "request_id" to "request", "session_id" to "session"))
            }
            assertThat(MDC.get("request_id")).isEqualTo("old")
            assertThat(MDC.get("unrelated")).isEqualTo("old")
        } finally { if (previous == null) MDC.clear() else MDC.setContextMap(previous) }
    }
}
