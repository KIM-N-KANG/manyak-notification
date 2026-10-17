package com.knk.manyak.notification.global.error

import org.junit.jupiter.api.Test
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

    @RestController
    class TypeConversionController {
        @GetMapping("/conversion")
        fun convert(@RequestParam count: Int) = count
    }
}
