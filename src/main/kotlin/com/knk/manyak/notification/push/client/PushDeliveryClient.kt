package com.knk.manyak.notification.push.client

import com.knk.manyak.notification.global.observability.CorrelationHeaders
import com.knk.manyak.notification.push.dto.PushDeliveryReport
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.URI

@Component
class PushDeliveryClient(
    builder: RestClient.Builder,
    @Value("\${manyak.server.internal-base-url:}") private val serverBaseUrl: String,
    @Value("\${manyak.internal.shared-secret:}") sharedSecret: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    // 공통 빌더의 연결 1초·읽기 2초 제한을 사용하며 재시도는 하지 않는다.
    private val restClient = builder.clone()
        .baseUrl(serverBaseUrl.trim())
        .defaultHeader("X-Manyak-Internal-Secret", sharedSecret)
        .requestInterceptor { request, body, execution ->
            CorrelationHeaders.forwardingHeadersFromMdc().forEach { (name, value) -> request.headers.set(name, value) }
            execution.execute(request, body)
        }
        .build()

    fun report(report: PushDeliveryReport) {
        try {
            val uri = URI.create(serverBaseUrl.trim())
            check(uri.scheme in setOf("http", "https") && uri.host != null) {
                "manyak.server.internal-base-url must include http or https scheme and host"
            }
            restClient.post().uri("/internal/push-deliveries")
                .contentType(MediaType.APPLICATION_JSON).body(report).retrieve().toBodilessEntity()
        } catch (ex: RuntimeException) {
            // 응답 본문과 예외 메시지에는 비밀값이 포함될 수 있어 상태 또는 클래스만 기록한다.
            log.warn("push_delivery_report_failed (messageId={}, error={})", report.messageId,
                if (ex is RestClientResponseException) ex.statusCode.value() else ex.javaClass.simpleName)
        }
    }
}
