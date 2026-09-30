package com.knk.manyak.notification.global.observability

import org.slf4j.MDC

object CorrelationHeaders {
    fun forwardingHeadersFromMdc(): Map<String, String> = buildMap {
        putIfPresent("X-Manyak-Request-Id", MDC.get("request_id"))
        putIfPresent("X-Manyak-Session-Id", MDC.get("session_id"))
        putIfPresent("X-Manyak-Device-Id-Hash", MDC.get("device_id_hash"))
    }

    private fun MutableMap<String, String>.putIfPresent(name: String, value: String?) {
        if (!value.isNullOrBlank() && value != "unknown") put(name, value)
    }
}
