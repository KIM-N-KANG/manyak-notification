package com.knk.manyak.notification.push.config

import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class FcmRetryTest {
    @ParameterizedTest @ValueSource(strings = ["0", "60"])
    fun `SDK는 503과 긴 Retry-After를 즉시 최종 실패로 반환한다`(retryAfter: String) {
        val requests = AtomicInteger()
        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): MockLowLevelHttpRequest {
                requests.incrementAndGet()
                return MockLowLevelHttpRequest().setResponse(MockLowLevelHttpResponse()
                    .setStatusCode(503).setContentType("application/json")
                    .setContent("""{"error":{"status":"UNAVAILABLE","message":"offline"}}""")
                    .addHeader("Retry-After", retryAfter))
            }
        }
        val options = FirebaseOptions.builder().setProjectId("test")
            .setCredentials(GoogleCredentials.create(AccessToken("test", Date.from(Instant.now().plusSeconds(3600)))))
            .setHttpTransport(transport).build()
        val app = FirebaseApp.initializeApp(options, UUID.randomUUID().toString())
        try {
            val messaging = FcmConfig().createMessaging(app)
            assertThatThrownBy { messaging.send(Message.builder().setToken("test-token").build()) }
                .isInstanceOf(FirebaseMessagingException::class.java)
            assertThat(requests.get()).isEqualTo(1)
        } finally { app.delete() }
    }
}
