package com.knk.manyak.notification.push.consumer

import com.knk.manyak.notification.push.dto.NotificationRequest
import com.knk.manyak.notification.push.dto.PushKind
import java.time.Instant
import java.util.UUID

data class PushMessage(
    val messageId: String,
    val recipientId: UUID,
    val kind: PushKind,
    val type: String,
    val data: Map<String, String>,
    val expiresAt: Instant?,
    val requestId: String,
    val sessionId: String,
    val schemaVersion: Int,
) {
    fun validate() {
        require(schemaVersion == 1 && messageId.isNotBlank() && requestId.isNotBlank() && sessionId.isNotBlank())
        require(type in setOf("STORY_COMPLETED", "ATTENDANCE_REMINDER", "PROMOTION") && data["type"] == type)
        require(kind == if (type == "STORY_COMPLETED") PushKind.SERVICE else PushKind.MARKETING)
    }

    fun request() = NotificationRequest(recipientId, kind, type, data, expiresAt)
}

enum class ConsumeResult { SUCCESS, RETRY, DISCARD }
enum class Claim { ACQUIRED, BUSY, DONE }

interface DeliveryStore {
    fun claim(messageId: String, owner: String): Claim
    fun complete(messageId: String, owner: String)
    fun release(messageId: String, owner: String)
    fun wasSent(messageId: String, token: String): Boolean
    fun recordSent(messageId: String, owner: String, token: String)
}
