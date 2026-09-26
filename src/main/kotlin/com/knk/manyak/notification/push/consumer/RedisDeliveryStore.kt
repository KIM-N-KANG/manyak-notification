package com.knk.manyak.notification.push.consumer

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration

@Component
class RedisDeliveryStore(
    private val redis: StringRedisTemplate,
    private val timing: ConsumerTimingProperties = ConsumerTimingProperties(),
) : DeliveryStore {
    override fun claim(messageId: String, owner: String): Claim {
        if (redis.opsForValue().setIfAbsent(processedKey(messageId), owner, Duration.ofMillis(timing.processingTtlMs)) == true) return Claim.ACQUIRED
        return if (redis.opsForValue().get(processedKey(messageId)) == DONE) Claim.DONE else Claim.BUSY
    }

    override fun complete(messageId: String, owner: String) {
        check(redis.execute(COMPLETE, listOf(processedKey(messageId)), owner, HISTORY_TTL.seconds.toString()) == 1L) {
            "Notification processing lease lost"
        }
    }

    override fun release(messageId: String, owner: String) {
        redis.execute(RELEASE, listOf(processedKey(messageId)), owner)
    }

    override fun wasSent(messageId: String, token: String): Boolean =
        redis.opsForSet().isMember(sentKey(messageId), hash(token)) == true

    override fun recordSent(messageId: String, owner: String, token: String) {
        check(redis.execute(SENT, listOf(processedKey(messageId), sentKey(messageId)), owner, hash(token), HISTORY_TTL.seconds.toString()) == 1L) {
            "Notification processing lease lost"
        }
    }

    companion object {
        val HISTORY_TTL: Duration = Duration.ofDays(7)
        const val DONE = "done"
        fun processedKey(id: String) = "notification:processed:$id"
        fun sentKey(id: String) = "notification:sent:$id"
        fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private val COMPLETE = DefaultRedisScript("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], 'done', 'EX', ARGV[2])
            return 1
        """.trimIndent(), Long::class.java)
        private val RELEASE = DefaultRedisScript("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
        """.trimIndent(), Long::class.java)
        private val SENT = DefaultRedisScript("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SADD', KEYS[2], ARGV[2])
            redis.call('EXPIRE', KEYS[2], ARGV[3])
            return 1
        """.trimIndent(), Long::class.java)
    }
}
