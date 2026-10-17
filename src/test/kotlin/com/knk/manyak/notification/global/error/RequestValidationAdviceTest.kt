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

        @GetMapping("/identifier")
        fun identifier(@RequestParam id: java.util.UUID) = id

        @GetMapping("/conversion")
        fun convert(@RequestParam count: Int) = count
    }
}
