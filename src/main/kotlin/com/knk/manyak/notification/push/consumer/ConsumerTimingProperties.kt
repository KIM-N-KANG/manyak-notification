package com.knk.manyak.notification.push.consumer

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 기본값: 선점 120초 < (5 - 1) × 60초 = 재시도 창 240초.
 * 새 기기 시작 예산 10초 + 마지막 SDK HTTP 3초(1+1+1, 재시도 0회/대기 0초)
 * + OAuth 갱신 최대 2회 × 40초(connect/read 각 20초, OAuth 재시도 비활성)
 * + 서버 토큰 정리 3초 + Redis 선점 응답/조회/기록/완료 각 2초 = 104초 < 120초.
 * 자격 조회는 10초 시작 예산에 포함한다. 늦어진 기기는 다음 전달에서 이어 보낸다.
 * JVM 정지나 네트워크의 비정상 지연으로 선점을 넘겨도 유실보다 중복을 허용한다.
 * 선점을 연장하지 않아 죽은 소비자의 메시지를 재시도 창 안에서 다시 선점할 수 있게 한다.
 */
@ConfigurationProperties("manyak.push.consumer")
data class ConsumerTimingProperties(
    val retryDelayMs: Long = 60_000,
    val retryAttempts: Int = 5,
    val processingTtlMs: Long = 120_000,
    val processingBudgetMs: Long = 10_000,
) {
    init {
        require(retryAttempts >= 2) { "retry-attempts must be at least 2 for abandoned lease recovery" }
        require(retryDelayMs > 0) { "retry-delay-ms must be positive" }
        val retryWindow = Math.multiplyExact((retryAttempts - 1).toLong(), retryDelayMs)
        require(processingTtlMs > 0 && processingTtlMs < retryWindow) {
            "processing-ttl-ms must be positive and strictly less than (retry-attempts - 1) * retry-delay-ms"
        }
        require(processingBudgetMs > 0 && processingBudgetMs < processingTtlMs) {
            "processing-budget-ms must be positive and strictly less than processing-ttl-ms"
        }
    }
}
