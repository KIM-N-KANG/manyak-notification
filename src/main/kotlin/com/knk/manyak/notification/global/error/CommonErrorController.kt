package com.knk.manyak.notification.global.error

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 컨테이너가 필터와 DispatcherServlet 전에 거절한 요청도 JSON으로 응답한다. */
@RestController
class CommonErrorController(private val advice: RequestValidationAdvice) : ErrorController {
    @RequestMapping("/error")
    fun error(request: HttpServletRequest, response: HttpServletResponse): org.springframework.http.ResponseEntity<ErrorResponse> {
        val status = HttpStatus.resolve(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int ?: 500)
            ?: HttpStatus.INTERNAL_SERVER_ERROR
        val path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String ?: request.requestURI
        if (!response.containsHeader("X-Manyak-Request-Id")) {
            response.setHeader("X-Manyak-Request-Id", request.getHeader("X-Manyak-Request-Id")?.takeIf { it.isNotBlank() }
                ?: "req_" + UUID.randomUUID().toString().replace("-", ""))
        }
        return advice.statusFailure(request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) as? Throwable, status, path)
    }
}
