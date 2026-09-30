package com.knk.manyak.notification.global.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class InternalSecretAuthenticationFilter(
    @Value("\${manyak.internal.shared-secret:}") sharedSecret: String,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val enabled = sharedSecret.isNotBlank()
    private val secretBytes = sharedSecret.toByteArray(Charsets.UTF_8)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.servletPath != "/internal" && !request.servletPath.startsWith("/internal/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val supplied = request.getHeader("X-Manyak-Internal-Secret")
        val status = when {
            !enabled -> HttpStatus.NOT_FOUND
            supplied == null || !MessageDigest.isEqual(secretBytes, supplied.toByteArray(Charsets.UTF_8)) -> HttpStatus.UNAUTHORIZED
            else -> null
        }
        if (status != null) {
            response.status = status.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write(objectMapper.writeValueAsString(mapOf(
                "status" to status.value(), "code" to status.name,
                "message" to if (status == HttpStatus.NOT_FOUND) "요청한 리소스를 찾을 수 없습니다." else "유효하지 않은 인증입니다.",
                "path" to request.requestURI,
            )))
            return
        }
        filterChain.doFilter(request, response)
    }
}
