package com.knk.manyak.notification.global.error

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.notification.push.dto.NotificationRequest
import jakarta.validation.Valid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

class RequestValidationAdviceTest {
    private val mvc = MockMvcBuilders.standaloneSetup(TypeConversionController())
        .setControllerAdvice(RequestValidationAdvice()).build()

    @Test
    fun `요청 파라미터 타입 변환 실패는 field와 message를 담는다`() {
        mvc.perform(get("/conversion").param("count", "invalid"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.path").value("/conversion"))
            .andExpect(jsonPath("$.details[0].field").value("count"))
            .andExpect(jsonPath("$.details[0].message").isNotEmpty)
    }

    @Test
    fun `타입 변환 로그는 파라미터와 기대 타입으로 원인을 구분하고 거절 값을 숨긴다`() = captureWarnings { logs ->
        mvc.perform(get("/conversion").param("count", "token-secret\r\nforged"))
            .andExpect(status().isBadRequest)
        mvc.perform(get("/identifier").param("id", "other-secret"))
            .andExpect(status().isBadRequest)
        assertThat(logs.map { it.formattedMessage }).satisfiesExactly(
            { assertThat(it).contains("MethodArgumentTypeMismatchException", "parameter=count", "expectedType=int") },
            { assertThat(it).contains("MethodArgumentTypeMismatchException", "parameter=id", "expectedType=UUID") },
        )
    }

    @Test
    fun `선언적 검증 로그는 필드와 제약을 기록하고 본문 값은 숨긴다`() = captureWarnings { logs ->
        mvc.perform(post("/validation").contentType(MediaType.APPLICATION_JSON)
            .content("""{"recipientId":"00000000-0000-0000-0000-000000000001","kind":"SERVICE","type":" ","data":{"token":"token-secret"}}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
            .andExpect(jsonPath("$.details[0].field").value("type"))
        assertThat(logs.single().formattedMessage).contains("MethodArgumentNotValidException", "field=type", "constraint=NotBlank")
    }

    @Test
    fun `본문 파싱 로그는 원인 클래스와 필드 위치로 실패를 구분한다`() = captureWarnings { logs ->
        listOf(
            """{"recipientId":"token-secret"}""",
            """{"recipientId":"00000000-0000-0000-0000-000000000001","kind":"other-secret"}""",
            """{"broken-secret": """,
            "",
        ).forEach { body ->
            mvc.perform(post("/validation").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.details").isEmpty)
        }
        assertThat(logs).hasSize(4)
        assertThat(logs[0].formattedMessage).contains("cause=InvalidFormatException", "path=recipientId")
        assertThat(logs[1].formattedMessage).contains("cause=InvalidFormatException", "path=kind")
        assertThat(logs[2].formattedMessage).contains("cause=UnexpectedEndOfInputException", "path=<root>")
        assertThat(logs[3].formattedMessage).contains("cause=HttpMessageNotReadableException", "path=<root>")
    }

    @Test
    fun `파싱 경로의 사용자 맵 키도 로그에서 숨긴다`() = captureWarnings { logs ->
        mvc.perform(post("/validation").contentType(MediaType.APPLICATION_JSON)
            .content("""{"recipientId":"00000000-0000-0000-0000-000000000001","kind":"SERVICE","type":"STORY_COMPLETED","data":{"token-secret\\r\\nforged":{}}}"""))
            .andExpect(status().isBadRequest)
        assertThat(logs.single().formattedMessage).contains("path=data.<key>")
    }

    @Test
    fun `MVC 표준 실패는 상태와 공통 본문을 유지하고 민감값 없이 한 줄 경고를 남긴다`() = captureWarnings { logs ->
        val cases = listOf(
            Triple(get("/validation"), 405, "METHOD_NOT_ALLOWED"),
            Triple(post("/validation").contentType(MediaType.TEXT_PLAIN).content("token-secret"), 415, "UNSUPPORTED_MEDIA_TYPE"),
            Triple(get("/missing-secret"), 404, "NOT_FOUND"),
            Triple(get("/conversion").param("count", "1").accept(MediaType.IMAGE_PNG), 406, "NOT_ACCEPTABLE"),
            Triple(get("/conversion"), 400, "BAD_REQUEST"),
        )
        cases.forEach { (request, code, name) ->
            mvc.perform(request).andExpect(status().`is`(code))
                .andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.code").value(name))
                .andExpect(jsonPath("$.timestamp").isNotEmpty)
                .andExpect(jsonPath("$.path").isNotEmpty)
                .andExpect(jsonPath("$.message").isNotEmpty)
                .andExpect(jsonPath("$.details").isArray)
        }
        assertThat(logs).hasSize(cases.size)
    }

    @Test
    fun `JSON을 제외한 Accept에서도 파싱 및 타입 검증 오류 본문을 반환한다`() {
        mvc.perform(get("/conversion").param("count", "invalid").accept(MediaType.IMAGE_PNG))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        mvc.perform(post("/validation").contentType(MediaType.APPLICATION_JSON).content("{").accept(MediaType.IMAGE_PNG))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
    }

    @Test
    fun `예상하지 못한 예외는 고정 500 본문과 민감값을 제거한 ERROR 스택을 남긴다`() {
        val logger = LoggerFactory.getLogger(RequestValidationAdvice::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            mvc.perform(get("/unexpected"))
                .andExpect(status().isInternalServerError)
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.path").value("/unexpected"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty)
                .andExpect(jsonPath("$.details").isEmpty)
            val event = appender.list.single()
            assertThat(event.throwableProxy.cause.message).isEqualTo(IllegalArgumentException::class.java.name)
            assertThat(event.throwableProxy.cause.stackTraceElementProxyArray.map { it.stackTraceElement })
                .containsExactly(*event.throwableProxy.stackTraceElementProxyArray.map { it.stackTraceElement }.toTypedArray())
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.throwableProxy.stackTraceElementProxyArray).isNotEmpty
            assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.throwableProxy))
                .doesNotContain("secret", "forged")
            assertThat(event.formattedMessage).contains("IllegalStateException").doesNotContain("secret", "forged")
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `같은 래퍼의 서로 다른 원인과 suppressed 발생 위치를 메시지 없이 보존한다`() {
        val logger = LoggerFactory.getLogger(RequestValidationAdvice::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            listOf(java.net.ConnectException("connect-secret"), java.net.SocketTimeoutException("timeout-secret")).forEach { cause ->
                cause.stackTrace = arrayOf(StackTraceElement("Origin", "connect", "Origin.kt", 42))
                val outer = IllegalStateException("outer-secret", cause)
                outer.addSuppressed(IllegalArgumentException("suppressed-secret").apply {
                    stackTrace = arrayOf(StackTraceElement("Cleanup", "close", "Cleanup.kt", 17))
                })
                RequestValidationAdvice().failure(outer, org.springframework.mock.web.MockHttpServletRequest())
            }
            val proxies = appender.list.map { it.throwableProxy }
            assertThat(proxies.map { it.cause.message }).containsExactly("java.net.ConnectException", "java.net.SocketTimeoutException")
            proxies.forEach {
                assertThat(it.cause.stackTraceElementProxyArray.single().stackTraceElement.lineNumber).isEqualTo(42)
                assertThat(it.suppressed.single().message).isEqualTo("java.lang.IllegalArgumentException")
                assertThat(it.suppressed.single().stackTraceElementProxyArray.single().stackTraceElement.lineNumber).isEqualTo(17)
                assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(it)).doesNotContain("secret")
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `예외 그래프의 순환과 깊이 및 suppressed 수를 제한한다`() {
        val first = IllegalStateException("first-secret")
        val second = IllegalArgumentException("second-secret", first)
        first.initCause(second)
        first.addSuppressed(second)
        val cycle = sanitizedThrowable(first)
        assertThat(cycle.cause!!.cause!!.message).isEqualTo("[exception graph truncated]")
        assertThat(cycle.suppressed.single().message).isEqualTo("[exception graph truncated]")
        var deep: Throwable = IllegalStateException("secret")
        repeat(100) { deep = IllegalStateException("secret", deep) }
        assertThat(generateSequence(sanitizedThrowable(deep)) { it.cause }.count()).isEqualTo(17)
        val wide = IllegalStateException("secret").apply { repeat(100) { addSuppressed(IllegalArgumentException("secret")) } }
        assertThat(sanitizedThrowable(wide).suppressed).hasSize(16)
    }

    private fun captureWarnings(assertions: (List<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(RequestValidationAdvice::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            assertions(appender.list)
            assertThat(appender.list).isNotEmpty.allSatisfy {
                assertThat(it.level).isEqualTo(Level.WARN)
                assertThat(it.throwableProxy).isNull()
                assertThat(it.formattedMessage).doesNotContain("secret", "forged", "\r", "\n")
                assertThat(it.argumentArray.joinToString()).doesNotContain("secret", "forged", "\r", "\n")
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @RestController
    class TypeConversionController {
        @PostMapping("/validation")
        fun validate(@Valid @RequestBody request: NotificationRequest) = request

        @GetMapping("/unexpected")
        fun unexpected(): String = throw IllegalStateException("token-secret\r\nforged", IllegalArgumentException("other-secret"))

        @GetMapping("/identifier")
        fun identifier(@RequestParam id: java.util.UUID) = id

        @GetMapping("/conversion")
        fun convert(@RequestParam count: Int) = count
    }
}
