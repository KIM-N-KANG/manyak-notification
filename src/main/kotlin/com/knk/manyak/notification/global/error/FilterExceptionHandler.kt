package com.knk.manyak.notification.global.error

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/** 인증 선행 거절 이후, MVC 앞의 폼 파싱 등을 공통 오류로 변환한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class FilterExceptionHandler(private val advice: RequestValidationAdvice, private val mapper: ObjectMapper) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            chain.doFilter(request, response)
        } catch (ex: Exception) {
            if (response.isCommitted) throw ex
            val result = if (ex is HttpMessageNotReadableException) advice.unreadable(ex, request) else advice.failure(ex, request)
            response.resetBuffer()
            response.status = result.statusCode.value()
            result.headers.forEach { name, values -> values.forEach { response.addHeader(name, it) } }
            response.characterEncoding = "UTF-8"
            response.writer.write(mapper.writeValueAsString(result.body))
        }
    }
}
