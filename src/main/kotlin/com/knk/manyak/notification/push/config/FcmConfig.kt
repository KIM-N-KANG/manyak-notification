package com.knk.manyak.notification.push.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FcmConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun firebaseMessaging(
        @Value("\${manyak.push.fcm.service-account-json:}") serviceAccountJson: String,
    ): FirebaseMessaging? {
        val json = serviceAccountJson.trim()
        if (json.isEmpty()) {
            log.info("FCM 서비스 계정이 설정되지 않아 푸시 발송을 비활성화합니다.")
            return null
        }
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(json.byteInputStream()))
            .build()
        // FirebaseApp은 JVM 전역 싱글턴이라 두 번 initializeApp 하면 IllegalStateException이다(컨텍스트 재기동 대비).
        val app = FirebaseApp.getApps().firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(options)
        return FirebaseMessaging.getInstance(app)
    }
}
