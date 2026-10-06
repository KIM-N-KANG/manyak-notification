package com.knk.manyak.notification.global.observability

import io.micrometer.context.ContextSnapshotFactory
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import org.slf4j.MDC
import org.springframework.core.task.TaskDecorator

/** 재시도 스케줄러의 제출 시점 관측과 기존 상관 MDC를 작업 동안만 복원한다. */
class ObservationTaskDecorator : TaskDecorator {
    private val snapshots = ContextSnapshotFactory.builder()
        .captureKeyPredicate { it == ObservationThreadLocalAccessor.KEY }.build()

    override fun decorate(runnable: Runnable): Runnable {
        val snapshot = snapshots.captureAll()
        val captured = MDC.getCopyOfContextMap()
        return Runnable {
            val previous = MDC.getCopyOfContextMap()
            try {
                if (captured == null) MDC.clear() else MDC.setContextMap(captured)
                snapshot.setThreadLocals().use { runnable.run() }
            } finally {
                if (previous == null) MDC.clear() else MDC.setContextMap(previous)
            }
        }
    }
}
