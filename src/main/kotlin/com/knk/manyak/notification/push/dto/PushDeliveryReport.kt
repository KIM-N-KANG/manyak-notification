package com.knk.manyak.notification.push.dto

import java.time.Instant
import java.util.UUID

enum class PushDeliveryOutcome { DELIVERED, UNDELIVERED, DISCARDED }

data class PushDeliveryReport(
    val messageId: String,
    val type: String,
    val kind: PushKind,
    val recipientId: UUID,
    val campaignId: String?,
    val outcome: PushDeliveryOutcome,
    val reason: String,
    val deliveredDevices: Int,
    val failedDevices: Int,
    val completedAt: Instant,
)
