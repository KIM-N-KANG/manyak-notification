package com.knk.manyak.notification.global.error

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import tools.jackson.core.JacksonException

@RestControllerAdvice
class RequestValidationAdvice {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        badRequest(ex, request, ex.bindingResult.fieldErrors.map {
            FieldErrorDetail(it.field, it.defaultMessage ?: "요청 값이 올바르지 않습니다.")
        }, ex.bindingResult.fieldErrors.joinToString("; ") {
            // 기본 메시지에는 보간된 거절 값이 들어갈 수 있으므로 제약 코드만 사용한다.
            "field=${it.field}, constraint=${it.code ?: "unknown"}"
        })

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(ex: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
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
    fun typeMismatch(ex: MethodArgumentTypeMismatchException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        badRequest(ex, request, listOf(FieldErrorDetail(ex.name, "요청 값의 형식이 올바르지 않습니다.")),
            "parameter=${ex.name}, expectedType=${ex.requiredType?.simpleName ?: "unknown"}")

    @ExceptionHandler(Exception::class)
    fun failure(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val mvcError = ex as? org.springframework.web.ErrorResponse
        val status = mvcError?.statusCode ?: HttpStatus.INTERNAL_SERVER_ERROR
        val httpStatus = HttpStatus.resolve(status.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        return statusFailure(ex, httpStatus, request.requestURI, mvcError?.headers ?: HttpHeaders())
    }

    internal fun statusFailure(ex: Throwable?, httpStatus: HttpStatus, path: String, originalHeaders: HttpHeaders = HttpHeaders()): ResponseEntity<ErrorResponse> {
        val message = when (httpStatus) {
            HttpStatus.BAD_REQUEST -> "요청 값이 올바르지 않습니다."
            HttpStatus.UNAUTHORIZED -> "유효하지 않은 인증입니다."
            HttpStatus.NOT_FOUND -> "요청한 리소스를 찾을 수 없습니다."
            HttpStatus.METHOD_NOT_ALLOWED -> "허용되지 않은 요청 메서드입니다."
            HttpStatus.UNSUPPORTED_MEDIA_TYPE -> "지원하지 않는 요청 본문 형식입니다."
            HttpStatus.NOT_ACCEPTABLE -> "지원하지 않는 응답 형식입니다."
            else -> if (httpStatus.is5xxServerError) "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요." else "요청을 처리할 수 없습니다."
        }
        if (httpStatus.is5xxServerError) {
            log.error("요청 처리 실패 (error={})", ex?.javaClass?.simpleName ?: httpStatus.name, ex?.let(::sanitizedThrowable))
        } else if (ex != null) {
            log.warn("요청 처리 실패 (error={}, message={})", ex.javaClass.simpleName, message)
        } else {
            log.debug("요청 처리 실패 (status={})", httpStatus.value())
        }
        val headers = HttpHeaders().apply {
            putAll(originalHeaders)
            contentType = MediaType.APPLICATION_JSON
        }
        return ResponseEntity(ErrorResponse(httpStatus.value(), httpStatus.name, message, path), headers, httpStatus)
    }

    private fun badRequest(
        ex: Exception,
        request: HttpServletRequest,
        details: List<FieldErrorDetail> = emptyList(),
        summary: String,
    ): ResponseEntity<ErrorResponse> {
        val message = "요청 값이 올바르지 않습니다."
        log.warn(
            "요청 검증 실패 (error={}, message={})", ex.javaClass.simpleName,
            summary.replace(Regex("[\\p{Cntrl}\\p{Zl}\\p{Zp}]"), " "),
        )
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(400, "BAD_REQUEST", message, request.requestURI, details))
    }
}
