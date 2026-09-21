package com.knk.manyak.notification.push.service

import com.google.firebase.messaging.AndroidConfig
import com.knk.manyak.notification.push.client.PushEligibilityClient
import com.knk.manyak.notification.push.dto.NotificationOutcome
import com.knk.manyak.notification.push.dto.NotificationRequest
import com.knk.manyak.notification.push.dto.NotificationResponse
import com.knk.manyak.notification.push.dto.PushKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class NotificationService(private val client: PushEligibilityClient, private val sender: FcmPushSender) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun send(request: NotificationRequest): NotificationResponse {
        val now = Instant.now()
        if (request.expiresAt?.let { !it.isAfter(now) } == true) {
            return NotificationResponse(NotificationOutcome.EXPIRED, "EXPIRED")
        }
        val eligibility = try {
            client.getEligibility(request.recipientId, request.kind, now)
        } catch (ex: RuntimeException) {
            // 통신 오류를 허용으로 대체하면 동의 철회나 계정 정지를 무시하고 발송하게 된다.
            log.warn("발송 자격 조회에 실패했습니다. (recipientId={}, error={})", request.recipientId, ex.javaClass.simpleName)
            return NotificationResponse(NotificationOutcome.FAILED, "ELIGIBILITY_UNAVAILABLE")
        }
        if (!eligibility.allowed) return NotificationResponse(NotificationOutcome.SKIPPED, eligibility.reason)
        val sendAt = Instant.now()
        if (request.expiresAt?.let { !it.isAfter(sendAt) } == true) {
            return NotificationResponse(NotificationOutcome.EXPIRED, "EXPIRED")
        }
        return sender.sendToUser(
            request.recipientId, eligibility.tokens, request.data + ("type" to request.type),
            priority = if (request.kind == PushKind.MARKETING) AndroidConfig.Priority.NORMAL else AndroidConfig.Priority.HIGH,
            ttlMillis = request.expiresAt?.let { Duration.between(sendAt, it).toMillis() },
        )
    }
}
