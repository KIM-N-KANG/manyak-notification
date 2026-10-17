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
import tools.jackson.core.JacksonException

@RestControllerAdvice
class RequestValidationAdvice {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun validation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ErrorResponse =
        badRequest(ex, request, ex.bindingResult.fieldErrors.map {
            FieldErrorDetail(it.field, it.defaultMessage ?: "요청 값이 올바르지 않습니다.")
        }, ex.bindingResult.fieldErrors.joinToString("; ") {
            // 기본 메시지에는 보간된 거절 값이 들어갈 수 있으므로 제약 코드만 사용한다.
            "field=${it.field}, constraint=${it.code ?: "unknown"}"
        })

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun unreadable(ex: HttpMessageNotReadableException, request: HttpServletRequest): ErrorResponse {
        val cause = generateSequence<Throwable>(ex) { it.cause }.filterIsInstance<JacksonException>().firstOrNull()
        // pathReference와 원문 메시지는 맵 키나 요청 값을 포함할 수 있다.
        val path = cause?.path?.joinToString(".") { reference ->
            val source = reference.from()
            val type = (source as? Class<*>) ?: source?.javaClass
            val field = reference.propertyName
            when {
                field != null && type != null && !Map::class.java.isAssignableFrom(type) &&
                    type.declaredFields.any { it.name == field } -> field
                field != null -> "<key>"
                reference.index >= 0 -> "[${reference.index}]"
                else -> "<?>"
            }
        }?.ifEmpty { "<root>" } ?: "<root>"
        return badRequest(ex, request, summary = "cause=${(cause ?: ex).javaClass.simpleName}, path=$path")
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun typeMismatch(ex: MethodArgumentTypeMismatchException, request: HttpServletRequest): ErrorResponse =
        badRequest(ex, request, listOf(FieldErrorDetail(ex.name, "요청 값의 형식이 올바르지 않습니다.")),
            "parameter=${ex.name}, expectedType=${ex.requiredType?.simpleName ?: "unknown"}")

    private fun badRequest(
        ex: Exception,
        request: HttpServletRequest,
        details: List<FieldErrorDetail> = emptyList(),
        summary: String,
    ): ErrorResponse {
        val message = "요청 값이 올바르지 않습니다."
        log.warn(
            "요청 검증 실패 (error={}, message={})", ex.javaClass.simpleName,
            summary.replace(Regex("[\\p{Cntrl}\\p{Zl}\\p{Zp}]"), " "),
        )
        return ErrorResponse(400, "BAD_REQUEST", message, request.requestURI, details)
    }
}
