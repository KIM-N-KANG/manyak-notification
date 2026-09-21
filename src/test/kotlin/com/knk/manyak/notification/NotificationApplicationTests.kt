package com.knk.manyak.notification

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationApplicationTests {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `컨텍스트가 로드되고 헬스체크가 UP을 반환한다`() {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health")).GET().build()
        val response = HttpClient.newHttpClient().use { client ->
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("""{"status":"UP"}""")
    }
}
