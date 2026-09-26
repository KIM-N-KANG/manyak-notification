package com.knk.manyak.notification.push.consumer

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.concurrent.Executors

@Testcontainers
class RedisDeliveryStoreTest {
    companion object {
        @Container @JvmStatic val redis = GenericContainer("redis:7-alpine").withExposedPorts(6379)
    }
    private lateinit var factory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var store: RedisDeliveryStore
    @BeforeEach fun setup() {
        factory = LettuceConnectionFactory(redis.host, redis.getMappedPort(6379)).apply { afterPropertiesSet(); start() }
        template = StringRedisTemplate(factory)
        template.connectionFactory!!.connection.use { it.serverCommands().flushDb() }
        store = RedisDeliveryStore(template)
    }
    @AfterEach fun close() { factory.destroy() }

    @Test fun `동시 SET NX는 한 소비자만 선점하고 15분 TTL을 둔다`() {
        Executors.newFixedThreadPool(8).use { pool ->
            val results = (1..20).map { pool.submit<Claim> { store.claim("id", "$it") } }.map { it.get() }
            assertThat(results.count { it == Claim.ACQUIRED }).isEqualTo(1)
            assertThat(results.count { it == Claim.BUSY }).isEqualTo(19)
        }
        assertThat(template.getExpire(RedisDeliveryStore.processedKey("id"))).isBetween(895, 900)
    }

    @Test fun `완료와 토큰 해시는 7일 보존하고 원본 토큰은 저장하지 않는다`() {
        store.claim("id", "owner")
        store.recordSent("id", "owner", "secret-token")
        store.complete("id", "owner")
        assertThat(store.claim("id", "other")).isEqualTo(Claim.DONE)
        assertThat(template.opsForSet().members(RedisDeliveryStore.sentKey("id"))).containsExactly(RedisDeliveryStore.hash("secret-token"))
        assertThat(store.wasSent("id", "secret-token")).isTrue()
        listOf(RedisDeliveryStore.processedKey("id"), RedisDeliveryStore.sentKey("id")).forEach {
            assertThat(template.getExpire(it)).isBetween(604795, 604800)
        }
    }

    @Test fun `재시도 해제와 만료 후 새 선점을 이전 소유자가 지우거나 완료할 수 없다`() {
        store.claim("id", "first")
        store.release("id", "first")
        assertThat(store.claim("id", "second")).isEqualTo(Claim.ACQUIRED)
        template.expire(RedisDeliveryStore.processedKey("id"), Duration.ofMillis(1))
        org.awaitility.Awaitility.await().until { !template.hasKey(RedisDeliveryStore.processedKey("id")) }
        assertThat(store.claim("id", "third")).isEqualTo(Claim.ACQUIRED)
        store.release("id", "second")
        assertThatThrownBy { store.complete("id", "second") }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { store.recordSent("id", "second", "token") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(template.opsForValue().get(RedisDeliveryStore.processedKey("id"))).isEqualTo("third")
    }
}
