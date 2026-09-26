package com.knk.manyak.notification.push.service

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.WebpushConfig
import com.google.firebase.messaging.WebpushNotification
import com.google.firebase.messaging.WebpushFcmOptions
import com.google.firebase.messaging.MessagingErrorCode
import com.knk.manyak.notification.push.client.PushEligibilityClient
import com.knk.manyak.notification.push.dto.NotificationOutcome
import com.knk.manyak.notification.push.dto.NotificationResponse
import com.knk.manyak.notification.push.dto.PushEligibilityToken
import com.knk.manyak.notification.push.dto.PushPlatform
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class FcmPushSender(
    private val messaging: FirebaseMessaging?,
    private val client: PushEligibilityClient,
    private val meterRegistry: MeterRegistry,
    @Value("\${manyak.push.web.icon-url:https://manyak.app/icons/icon-192.png}")
    private val webIconUrl: String = "https://manyak.app/icons/icon-192.png",
    @Value("\${manyak.push.web-base-url:https://manyak.app}")
    private val webBaseUrl: String = "https://manyak.app",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun sendToUser(
        recipientId: UUID,
        tokens: List<PushEligibilityToken>,
        data: Map<String, String>,
        priority: AndroidConfig.Priority = AndroidConfig.Priority.HIGH,
        ttlMillis: Long? = null,
        alreadySent: (String) -> Boolean = { false },
        onSent: (String) -> Unit = {},
        beforeSend: () -> Unit = {},
        expiresAt: Instant? = null,
    ): NotificationResponse {
        val messaging = this.messaging ?: return NotificationResponse(NotificationOutcome.SKIPPED, "FCM_DISABLED")
        if (tokens.isEmpty()) return NotificationResponse(NotificationOutcome.SKIPPED, "NO_TOKENS")
        // 기기 공유나 지연 수신 때 앱이 다른 회원의 알림을 거르도록 호출자 값보다 우선한다.
        val payload = data + (KEY_RECIPIENT_ID to recipientId.toString())
        val outcomes = tokens.distinctBy { it.token }.map {
            beforeSend()
            if (alreadySent(it.token)) OUTCOME_SUCCESS
            else {
                val remaining = expiresAt?.let { end -> Duration.between(Instant.now(), end).toMillis() }
                if (remaining != null && remaining <= 0) OUTCOME_FAILURE
                else sendTo(messaging, recipientId, it, payload, priority, remaining ?: ttlMillis).also { result ->
                    // SDK 예외 처리 밖에서 기록한다. 저장 실패를 영구 발송 실패로 삼키면 안 된다.
                    if (result == OUTCOME_SUCCESS) onSent(it.token)
                }
            }
        }
        val sent = outcomes.count { it == OUTCOME_SUCCESS }
        val unregistered = outcomes.count { it == OUTCOME_UNREGISTERED }
        val failed = outcomes.count { it == OUTCOME_FAILURE || it == OUTCOME_RETRY }
        return NotificationResponse(
            outcome = if (sent > 0) NotificationOutcome.SENT else NotificationOutcome.FAILED,
            reason = when {
                sent == outcomes.size -> "OK"
                sent > 0 -> "PARTIAL_FAILURE"
                else -> "NO_DELIVERIES"
            },
            sent = sent, unregistered = unregistered, failed = failed,
            retryable = outcomes.count { it == OUTCOME_RETRY },
        )
    }

    private fun sendTo(
        messaging: FirebaseMessaging,
        recipientId: UUID,
        deviceToken: PushEligibilityToken,
        data: Map<String, String>,
        priority: AndroidConfig.Priority,
        ttlMillis: Long?,
    ): String {
        try {
            val builder = Message.builder().setToken(deviceToken.token).putAllData(data)
            when (deviceToken.platform) {
                PushPlatform.ANDROID -> builder.setAndroidConfig(
                    AndroidConfig.builder().setPriority(priority)
                        .apply { ttlMillis?.let { setTtl(it.coerceAtMost(MAX_TTL_MILLIS)) } }
                        .build(),
                )
                PushPlatform.WEB -> builder.setWebpushConfig(
                    WebpushConfig.builder()
                        .setNotification(
                            WebpushNotification.builder()
                                .setTitle(data["title"])
                                .setBody(data["body"])
                                .setIcon(webIconUrl)
                                .build(),
                        )
                        .setFcmOptions(WebpushFcmOptions.withLink(data["deepLink"] ?: webBaseUrl))
                        .build(),
                )
            }
            val message = builder.build()
            messaging.send(message)
            count(OUTCOME_SUCCESS)
            return OUTCOME_SUCCESS
        } catch (ex: FirebaseMessagingException) {
            // UNREGISTERED만 지운다. INVALID_ARGUMENT는 토큰 형식 오류뿐 아니라 **우리 페이로드 오류**에도 오므로,
            // 그걸 삭제 신호로 쓰면 서버 버그 하나가 회원 전체의 토큰을 지운다. 형식이 깨진 토큰은 앱이 FCM SDK에서
            // 받은 값을 그대로 올리는 경로라 실제로 드물고, 남더라도 발송 실패 메트릭으로 드러난다.
            if (ex.messagingErrorCode == MessagingErrorCode.UNREGISTERED) {
                // 정리 오류는 형제 catch에 잡히지 않으므로 별도로 가둬야 다음 기기 발송이 끊기지 않는다.
                try {
                    client.deleteInvalidToken(deviceToken.token)
                    count(OUTCOME_UNREGISTERED)
                    log.info("무효 FCM 토큰을 정리했습니다. (recipientId={}, token={})", recipientId, mask(deviceToken.token))
                    return OUTCOME_UNREGISTERED
                } catch (cleanupEx: RuntimeException) {
                    count(OUTCOME_FAILURE)
                    log.warn(
                        "무효 FCM 토큰 정리에 실패했습니다. (recipientId={}, token={}, error={})",
                        recipientId, mask(deviceToken.token), cleanupEx.javaClass.simpleName,
                    )
                    return OUTCOME_FAILURE
                }
            } else {
                count(OUTCOME_FAILURE)
                log.warn(
                    "FCM 발송에 실패했습니다. (recipientId={}, token={}, code={}, error={})",
                    recipientId, mask(deviceToken.token), ex.messagingErrorCode, ex.javaClass.simpleName,
                )
                val status = ex.httpResponse?.statusCode
                return if (ex.messagingErrorCode == MessagingErrorCode.INVALID_ARGUMENT || status in listOf(400, 401, 403, 404) ||
                    ex.messagingErrorCode in listOf(MessagingErrorCode.SENDER_ID_MISMATCH, MessagingErrorCode.THIRD_PARTY_AUTH_ERROR)) OUTCOME_FAILURE
                else OUTCOME_RETRY
            }
        } catch (ex: RuntimeException) {
            // SDK 내부 오류·잘못된 메시지 조립 등. 한 기기 실패가 다른 기기 발송을 막지 않는다.
            count(OUTCOME_FAILURE)
            log.warn(
                "FCM 발송 중 예외가 났습니다. (recipientId={}, token={}, error={})",
                recipientId, mask(deviceToken.token), ex.javaClass.simpleName,
            )
            return if (ex is IllegalArgumentException) OUTCOME_FAILURE else OUTCOME_RETRY
        }
    }

    private fun count(outcome: String) {
        Counter.builder(METRIC_PUSH_SEND_RESULT).tag("outcome", outcome).register(meterRegistry).increment()
    }

    // 토큰은 그 기기로 푸시를 보낼 수 있는 주소라 로그에 전체를 남기지 않는다.
    private fun mask(token: String): String = token.take(TOKEN_LOG_PREFIX) + "…"

    companion object {
        /** 모든 시나리오 데이터에 모듈이 덧붙이는 수신 회원 `public_id` 키(스펙 §4-3-5 푸시 발송 모듈). */
        const val KEY_RECIPIENT_ID = "recipientId"
        const val METRIC_PUSH_SEND_RESULT = "manyak.push.send.result"

        /**
         * FCM이 허용하는 Android TTL 상한인 4주. SDK는 음수만 막고 상한은 검사하지 않아서,
         * 더 큰 값을 그대로 넘기면 FCM이 INVALID_ARGUMENT로 거절해 발송이 통째로 실패한다.
         * 보관 기간을 넘겨 못 받는 것과 상한으로 깎여 받는 것 중에는 후자가 낫다.
         */
        const val MAX_TTL_MILLIS = 28L * 24 * 60 * 60 * 1000
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_UNREGISTERED = "unregistered"
        const val OUTCOME_FAILURE = "failure"
        private const val OUTCOME_RETRY = "retry"
        val OUTCOMES = listOf(OUTCOME_SUCCESS, OUTCOME_UNREGISTERED, OUTCOME_FAILURE)
        private const val TOKEN_LOG_PREFIX = 12
    }
}
