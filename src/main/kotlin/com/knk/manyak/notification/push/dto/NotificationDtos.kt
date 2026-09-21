package com.knk.manyak.notification.push.dto

import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

enum class PushKind { SERVICE, MARKETING }
enum class PushPlatform { ANDROID, WEB }
enum class NotificationOutcome { SENT, SKIPPED, EXPIRED, FAILED }

data class NotificationRequest(
    val recipientId: UUID,
    val kind: PushKind,
    @field:NotBlank val type: String,
    val data: Map<String, String>,
    val expiresAt: Instant? = null,
)

data class NotificationResponse(
    val outcome: NotificationOutcome,
    val reason: String,
    val sent: Int = 0,
    val unregistered: Int = 0,
    val failed: Int = 0,
)

data class PushEligibilityResponse(
    val allowed: Boolean,
    val reason: String,
    val tokens: List<PushEligibilityToken>,
)

data class PushEligibilityToken(val token: String, val platform: PushPlatform)
