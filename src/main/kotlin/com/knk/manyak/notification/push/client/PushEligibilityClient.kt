package com.knk.manyak.notification.push.client

import com.knk.manyak.notification.global.observability.CorrelationHeaders
import com.knk.manyak.notification.push.dto.PushEligibilityResponse
import com.knk.manyak.notification.push.dto.PushKind
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Instant
import java.util.UUID

@Component
class PushEligibilityClient(
    builder: RestClient.Builder,
    @Value("\${manyak.server.internal-base-url:}") private val serverBaseUrl: String,
    @Value("\${manyak.internal.shared-secret:}") sharedSecret: String,
) {
    private val restClient = builder.clone()
        .baseUrl(serverBaseUrl.trim())
        .defaultHeader("X-Manyak-Internal-Secret", sharedSecret)
        .requestInterceptor { request, body, execution ->
            CorrelationHeaders.forwardingHeadersFromMdc().forEach { (name, value) -> request.headers.set(name, value) }
            execution.execute(request, body)
        }
        .build()

    fun getEligibility(publicId: UUID, kind: PushKind, at: Instant): PushEligibilityResponse {
        requireServerUrl()
        return restClient.get()
            .uri { it.path("/internal/users/{publicId}/push-eligibility").queryParam("kind", kind).queryParam("at", at).build(publicId) }
            .accept(MediaType.APPLICATION_JSON).retrieve().body(PushEligibilityResponse::class.java)
            ?: throw IllegalStateException("Push eligibility response body is empty")
    }

    fun deleteInvalidToken(token: String) {
        requireServerUrl()
        restClient.method(HttpMethod.DELETE).uri("/internal/push-tokens")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("token" to token)).retrieve().toBodilessEntity()
    }

    private fun requireServerUrl() {
        // 설정 없이도 헬스체크는 기동하되 자격 조회를 생략하고 발송하는 일은 막는다.
        val uri = URI.create(serverBaseUrl.trim())
        check((uri.scheme == "http" || uri.scheme == "https") && uri.host != null) {
            "manyak.server.internal-base-url must include http or https scheme and host"
        }
    }
}
