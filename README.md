# manyak-notification

manyak-server에서 FCM 발송 실행을 분리할 알림 서비스입니다. 현재는 Spring Boot 부팅, 헬스체크, 요청 상관 헤더 처리만 구현했습니다. FCM 발송, 발송 API, DB, 큐는 후속 작업입니다.

## 로컬 실행

Java 21을 설치한 뒤 실행합니다. DB나 외부 서비스 설정은 필요하지 않습니다.

```sh
./gradlew test
./gradlew bootRun
```

다른 터미널에서 확인합니다. 기본 포트는 8080이며 응답은 `{"status":"UP"}`입니다.

```sh
curl -i http://localhost:8080/actuator/health
curl -i -H 'X-Manyak-Request-Id: req_test123' http://localhost:8080/actuator/health
```

`X-Manyak-Request-Id`는 응답 헤더에 그대로 돌아오며, 없거나 공백이면 `req_` 접두 UUID를 생성합니다. `X-Manyak-Session-Id`, `X-Manyak-Device-Id-Hash`는 받은 값을 MDC에 넣고 없거나 공백이면 `unknown`을 씁니다. 원본 기기 ID를 받거나 해시를 계산하지 않습니다.

## 로그 확인

기본 로컬 로그는 사람이 읽는 형식이고 `dev`, `prod`, `jsonlog` 프로파일에서는 JSON 한 줄입니다. 요청 처리 중 발생한 로그에 MDC의 `request_id`, `session_id`, `device_id_hash`가 붙습니다. 평소 헬스체크는 요청 로그를 남기지 않습니다. 아래처럼 필터의 DEBUG 로그를 켜면 위 curl 요청의 식별자를 확인할 수 있습니다.

```sh
./gradlew bootRun --args='--spring.profiles.active=jsonlog --logging.level.com.knk.manyak.notification.global.observability.RequestCorrelationFilter=DEBUG'
```

서버는 `Ctrl+C`로 종료합니다. `SERVER_PORT`로 포트를 바꿀 수 있습니다.

## 컨테이너

```sh
docker build -t manyak-notification .
docker run --rm -p 8080:8080 manyak-notification
```

Dockerfile은 Java 21 멀티 스테이지 빌드로 `build/libs/app.jar`를 만들고 비루트 사용자로 실행합니다. CI는 dev 대상 PR과 dev push에서 테스트만 실행합니다.
