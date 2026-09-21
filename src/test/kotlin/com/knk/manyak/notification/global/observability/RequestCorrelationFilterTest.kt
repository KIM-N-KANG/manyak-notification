package com.knk.manyak.notification.global.observability

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestCorrelationFilterTest {
    private val filter = RequestCorrelationFilter()

    @Test
    fun `받은 식별자를 응답과 MDC에 그대로 전달하고 종료 후 비운다`() {
        val request = MockHttpServletRequest().apply {
            addHeader("X-Manyak-Request-Id", "req_test123")
            addHeader("X-Manyak-Session-Id", "session_test")
            addHeader("X-Manyak-Device-Id-Hash", "device_hash_test")
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, _ ->
            assertThat(MDC.get("request_id")).isEqualTo("req_test123")
            assertThat(MDC.get("session_id")).isEqualTo("session_test")
            assertThat(MDC.get("device_id_hash")).isEqualTo("device_hash_test")
        })
        assertThat(response.getHeader("X-Manyak-Request-Id")).isEqualTo("req_test123")
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty()
    }

    @Test
    fun `누락된 요청 ID는 매번 생성하고 다른 식별자는 unknown으로 채운다`() {
        val ids = (1..2).map {
            val response = MockHttpServletResponse()
            filter.doFilter(MockHttpServletRequest(), response, FilterChain { _, _ ->
                assertThat(MDC.get("request_id")).matches("req_[0-9a-f]{32}")
                assertThat(MDC.get("session_id")).isEqualTo("unknown")
                assertThat(MDC.get("device_id_hash")).isEqualTo("unknown")
            })
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty()
            response.getHeader("X-Manyak-Request-Id")
        }
        assertThat(ids).doesNotHaveDuplicates()
        assertThat(ids).allSatisfy { assertThat(it).matches("req_[0-9a-f]{32}") }
    }

    @Test
    fun `체인에서 예외가 발생해도 MDC를 비운다`() {
        assertThatThrownBy {
            filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), FilterChain { _, _ ->
                throw IllegalStateException("test failure")
            })
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty()
    }
}
