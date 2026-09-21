package com.knk.manyak.notification.global.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** 서버가 전달한 상관 식별자를 요청 처리 동안만 MDC에 보관한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestCorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.headerOrNull("X-Manyak-Request-Id")
            ?: "req_" + UUID.randomUUID().toString().replace("-", "")
        response.setHeader("X-Manyak-Request-Id", requestId)
        MDC.put("request_id", requestId)
        MDC.put("session_id", request.headerOrNull("X-Manyak-Session-Id") ?: "unknown")
        // 이미 해시된 값만 받는다. 원본 기기 ID 처리와 해시는 서버의 책임이다.
        MDC.put("device_id_hash", request.headerOrNull("X-Manyak-Device-Id-Hash") ?: "unknown")
        try {
            // 로컬 검증 때만 켠다. 기본 INFO에서는 헬스체크 요청 로그를 남기지 않는다.
            log.debug("Request correlation initialized")
            filterChain.doFilter(request, response)
        } finally {
            // 서블릿 스레드가 다음 요청에 재사용될 때 식별자가 새지 않게 한다.
            MDC.clear()
        }
    }

    private fun HttpServletRequest.headerOrNull(name: String): String? =
        getHeader(name)?.takeIf { it.isNotBlank() }

    companion object {
        private val log = LoggerFactory.getLogger(RequestCorrelationFilter::class.java)
    }
}
