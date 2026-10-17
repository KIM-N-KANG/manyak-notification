package com.knk.manyak.notification.global.error

import java.time.Instant

data class ErrorResponse(
    val status: Int,
    val code: String,
    val message: String,
    val path: String,
    val details: List<FieldErrorDetail> = emptyList(),
    val timestamp: Instant = Instant.now(),
)

data class FieldErrorDetail(val field: String, val message: String)
