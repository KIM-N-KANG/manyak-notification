package com.knk.manyak.notification.push.consumer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.data.redis.port=1"])
abstract class NonLocalProfileTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var context: ApplicationContext
    @Test fun `Kafka 없이 리스너와 클라이언트가 생성되지 않고 health는 UP이다`() {
        assertThat(context.getBeansOfType(KafkaNotificationListener::class.java)).isEmpty()
        assertThat(context.getBeansOfType(KafkaListenerEndpointRegistry::class.java)).isEmpty()
        assertThat(context.getBeansOfType(KafkaTemplate::class.java)).isEmpty()
        HttpClient.newHttpClient().use { client ->
            val response = client.send(HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.body()).isEqualTo("""{"status":"UP"}""")
        }
    }
}
@ActiveProfiles("dev") class DevProfileTest : NonLocalProfileTest()
@ActiveProfiles("prod") class ProdProfileTest : NonLocalProfileTest()
