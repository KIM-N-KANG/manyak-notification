package com.knk.manyak.notification.push.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

// 첫 실패부터 increase로 집계되도록 카운터 시계열을 0으로 미리 만든다.
@Component
class PushSendMeters : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        listOf("success", "retry", "discard", "dlq").forEach { outcome ->
            Counter.builder(com.knk.manyak.notification.push.consumer.NotificationConsumer.METRIC)
                .description("큐 알림 소비 결과").tag("outcome", outcome).register(registry)
        }
        FcmPushSender.OUTCOMES.forEach { outcome ->
            Counter.builder(FcmPushSender.METRIC_PUSH_SEND_RESULT)
                .description("FCM 푸시 발송 결과")
                .tag("outcome", outcome)
                .register(registry)
        }
    }
}
