package com.knk.manyak.notification.push.controller

import com.knk.manyak.notification.push.dto.NotificationRequest
import com.knk.manyak.notification.push.dto.NotificationResponse
import com.knk.manyak.notification.push.service.NotificationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/notifications")
class NotificationController(private val service: NotificationService) {
    @PostMapping
    fun send(@Valid @RequestBody request: NotificationRequest): ResponseEntity<NotificationResponse> {
        val result = service.send(request)
        val status = if (result.reason == "ELIGIBILITY_UNAVAILABLE") HttpStatus.BAD_GATEWAY else HttpStatus.OK
        return ResponseEntity.status(status).body(result)
    }
}
