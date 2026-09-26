package com.knk.manyak.notification.push.consumer

import com.knk.manyak.notification.push.dto.NotificationOutcome
import com.knk.manyak.notification.push.service.NotificationService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class NotificationConsumer(
    private val service: NotificationService,
    private val store: DeliveryStore,
    private val registry: MeterRegistry,
    private val timing: ConsumerTimingProperties = ConsumerTimingProperties(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun onMessage(message: PushMessage): ConsumeResult = withCorrelation(message) {
        val owner = UUID.randomUUID().toString()
        var acquired = false
        val result = try {
            when (store.claim(message.messageId, owner)) {
                Claim.DONE -> ConsumeResult.SUCCESS
                Claim.BUSY -> ConsumeResult.RETRY
                Claim.ACQUIRED -> {
                    acquired = true
                    val deadline = System.nanoTime() + Duration.ofMillis(timing.processingBudgetMs).toNanos()
                    val response = service.send(
                        message.request(),
                        alreadySent = { store.wasSent(message.messageId, it) },
                        onSent = { store.recordSent(message.messageId, owner, it) },
                        beforeSend = { check(System.nanoTime() < deadline) { "Notification processing budget exhausted" } },
                    )
                    val outcome = when {
                        response.retryable > 0 || response.reason == "ELIGIBILITY_UNAVAILABLE" -> ConsumeResult.RETRY
                        response.outcome == NotificationOutcome.SENT -> ConsumeResult.SUCCESS
                        else -> ConsumeResult.DISCARD
                    }
                    log.info("큐 알림 처리 결과 (messageId={}, result={}, reason={})", message.messageId, outcome, response.reason)
                    if (outcome == ConsumeResult.RETRY) store.release(message.messageId, owner)
                    else store.complete(message.messageId, owner)
                    outcome
                }
            }
        } catch (ex: RuntimeException) {
            // Redis 장애 시 발송을 계속하지 않는다. 소유권을 확인해 남의 선점은 삭제하지 않는다.
            if (acquired) runCatching { store.release(message.messageId, owner) }
            log.warn("큐 알림 처리 재시도 (messageId={}, error={})", message.messageId, ex.javaClass.simpleName)
            ConsumeResult.RETRY
        }
        registry.counter(METRIC, "outcome", result.name.lowercase()).increment()
        result
    }

    companion object {
        const val METRIC = "manyak.push.consume.result"
        fun <T> withCorrelation(message: PushMessage, action: () -> T): T {
            val previous = MDC.getCopyOfContextMap()
            try {
                MDC.clear()
                MDC.put("request_id", message.requestId)
                MDC.put("session_id", message.sessionId)
                return action()
            } finally {
                if (previous == null) MDC.clear() else MDC.setContextMap(previous)
            }
        }
    }
}
