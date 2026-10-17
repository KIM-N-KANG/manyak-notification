package com.knk.manyak.notification.global.error

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class RequestValidationAdvice {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun validation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ErrorResponse =
        badRequest(ex, request, ex.bindingResult.fieldErrors.map {
            FieldErrorDetail(it.field, it.defaultMessage ?: "요청 값이 올바르지 않습니다.")
        })

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun unreadable(ex: HttpMessageNotReadableException, request: HttpServletRequest): ErrorResponse = badRequest(ex, request)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun typeMismatch(ex: MethodArgumentTypeMismatchException, request: HttpServletRequest): ErrorResponse =
        badRequest(ex, request, listOf(FieldErrorDetail(ex.name, "요청 값의 형식이 올바르지 않습니다.")))

    private fun badRequest(ex: Exception, request: HttpServletRequest, details: List<FieldErrorDetail> = emptyList()): ErrorResponse {
        // 파싱 예외 원문에는 요청 본문이 포함될 수 있어 고정 안내 문구만 기록한다.
        val message = "요청 값이 올바르지 않습니다."
        log.warn("요청 검증 실패 (error={}, message={})", ex.javaClass.simpleName, message)
        return ErrorResponse(400, "BAD_REQUEST", message, request.requestURI, details)
    }
}
