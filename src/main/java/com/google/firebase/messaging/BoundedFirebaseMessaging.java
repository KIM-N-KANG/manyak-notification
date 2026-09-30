package com.google.firebase.messaging;

import com.google.firebase.FirebaseApp;
import com.google.firebase.ImplFirebaseTrampolines;
import com.google.firebase.internal.ApiClientUtils;
import com.google.firebase.internal.RetryConfig;
import java.util.List;

/**
 * firebase-admin 9.10.0에는 FirebaseOptions의 공개 retry 설정이 없다.
 * 이 연결 클래스만 SDK package-private builder에 의존한다(리플렉션/전역 설정 변경 없음).
 * SDK 교체 시 FcmRetryTest로 실제 HTTP 호출 수와 Retry-After 처리를 검증해야 한다.
 */
public final class BoundedFirebaseMessaging {
    private BoundedFirebaseMessaging() { }

    public static FirebaseMessaging create(FirebaseApp app) {
        // SDK 최대 재시도 0회이므로 대기는 0초다. 500ms는 RetryConfig가 허용하는 최소 상한.
        // 일시 실패는 브로커의 60초 간격 재시도에 맡겨 선점 안에서 호출을 끝낸다.
        RetryConfig retries = RetryConfig.builder()
                .setMaxRetries(0)
                .setMaxIntervalMillis(500)
                .setRetryStatusCodes(List.of(503))
                .build();
        return FirebaseMessaging.builder()
                .setFirebaseApp(app)
                .setMessagingClient(() -> FirebaseMessagingClientImpl.builder()
                        .setProjectId(ImplFirebaseTrampolines.getProjectId(app))
                        .setRequestFactory(ApiClientUtils.newAuthorizedRequestFactory(app, retries))
                        .setChildRequestFactory(ApiClientUtils.newUnauthorizedRequestFactory(app))
                        .setJsonFactory(app.getOptions().getJsonFactory())
                        .build())
                .setInstanceIdClient(() -> InstanceIdClientImpl.fromApp(app))
                .build();
    }
}
