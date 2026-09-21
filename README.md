# manyak-notification

manyak-server에서 발송 자격을 확인한 뒤 FCM으로 동기 발송하는 알림 서비스입니다. 회원과 토큰은 서버가 관리하며 이 서비스에는 DB와 큐가 없습니다. Kotlin 2.2.21, Spring Boot 4.0.6, Java 21을 사용합니다.

## 설정

| 환경변수 | Spring 설정 | 비어 있을 때 동작 |
| --- | --- | --- |
| `MANYAK_INTERNAL_SHARED_SECRET` | `manyak.internal.shared-secret` | `/internal/**`가 404. 서버와 같은 시크릿 사용 |
| `MANYAK_SERVER_INTERNAL_BASE_URL` | `manyak.server.internal-base-url` | 기동과 헬스체크 가능. 만료되지 않은 발송 요청은 자격 조회 실패로 502, FCM 호출 없음 |
| `MANYAK_FCM_SERVICE_ACCOUNT_JSON` | `manyak.push.fcm.service-account-json` | Firebase 빈을 만들지 않음. 자격 허용 후에도 `SKIPPED`, `FCM_DISABLED`, 발송 0건 |

Firebase 서비스 계정은 앱과 웹이 사용하는 Firebase 프로젝트에서 발급합니다. JSON이 비어 있지 않지만 잘못된 형식이면 기동에 실패합니다. 실제 비밀값은 커밋하지 않는 로컬 환경에서 주입합니다.

웹 알림 아이콘은 `MANYAK_PUSH_WEB_ICON_URL`(`manyak.push.web.icon-url`, 기본 `https://manyak.app/icons/icon-192.png`), `deepLink`가 없는 웹 알림의 클릭 링크는 `MANYAK_PUSH_WEB_BASE_URL`(`manyak.push.web-base-url`, 기본 `https://manyak.app`)로 설정합니다.

## 로컬 실행

Java 21을 설치하고 위 세 환경변수를 주입합니다. 서버는 `DELETE /internal/push-tokens`를 지원하는 버전이어야 합니다. 연결은 3초, 읽기는 5초에 타임아웃됩니다.

```sh
export MANYAK_SERVER_INTERNAL_BASE_URL=http://localhost:8080
# MANYAK_INTERNAL_SHARED_SECRET, MANYAK_FCM_SERVICE_ACCOUNT_JSON은 로컬 비밀값으로 주입
./gradlew test
SERVER_PORT=8081 ./gradlew bootRun
```

서버가 8080을 사용하므로 알림 서비스는 로컬에서 8081을 씁니다. 환경변수를 전혀 주입하지 않아도 헬스체크를 확인할 수 있습니다.

```sh
curl -i http://localhost:8081/actuator/health
# 200 {"status":"UP"}

curl -i -X POST http://localhost:8081/internal/notifications \
  -H 'Content-Type: application/json' -d '{}'
# 시크릿을 설정해 기동한 서비스에서는 헤더 누락으로 401. 시크릿 미설정 서비스는 404.
```

## 발송 API

`POST /internal/notifications`는 `X-Manyak-Internal-Secret` 헤더로 보호합니다. `recipientId`는 회원 publicId UUID이며 `kind`, `type`, `data`까지 모두 필수입니다. `expiresAt`은 선택이며 UTC ISO 8601 시각입니다.

```json
{
  "recipientId": "00000000-0000-4000-8000-000000000001",
  "kind": "SERVICE",
  "type": "STORY_COMPLETED",
  "data": {
    "title": "스토리 완성",
    "body": "새 스토리를 확인해 주세요.",
    "deepLink": "https://manyak.app"
  },
  "expiresAt": "2030-01-01T00:00:00Z"
}
```

`kind`는 `SERVICE` 또는 `MARKETING`입니다. 먼저 만료를 검사하고 현재 시각으로 자격 API를 조회합니다. 거절되면 보내지 않고, 허용된 토큰마다 한 번씩 FCM을 호출합니다. 자격 조회 중 만료되어도 발송하지 않습니다.

Android는 data-only이며 서비스 알림은 HIGH, 광고 알림은 NORMAL입니다. `expiresAt`을 지정하면 남은 시간을 Android TTL로 사용하고 생략하면 FCM 기본 TTL을 유지합니다. WEB은 제목, 본문, 아이콘, 클릭 링크를 가진 webpush notification입니다. 광고 표기 등 시나리오 문구는 호출자가 `data`에 준비합니다. 최상위 `type`과 `recipientId`는 같은 이름의 `data` 값보다 우선합니다.

```json
{"outcome":"SENT","reason":"OK","sent":2,"unregistered":0,"failed":0}
```

| outcome / reason | 의미 | HTTP |
| --- | --- | --- |
| `SENT` / `OK` | 모든 기기 발송 성공 | 200 |
| `SENT` / `PARTIAL_FAILURE` | 일부 성공. 결과 건수 확인 | 200 |
| `SKIPPED` / 자격 거절 사유 | 동의, 계정 상태 또는 토큰 조건 미충족 | 200 |
| `SKIPPED` / `FCM_DISABLED` | Firebase 미설정 | 200 |
| `EXPIRED` / `EXPIRED` | 유효 기간 경과 | 200 |
| `FAILED` / `NO_DELIVERIES` | FCM 성공 0건 | 200 |
| `FAILED` / `ELIGIBILITY_UNAVAILABLE` | 자격 조회 실패, 발송 없음 | 502 |

`UNREGISTERED`는 서버 삭제 API로 정리하며 삭제까지 성공하면 `unregistered`에 셉니다. 삭제 실패는 기존 서버 메트릭 관례대로 `failed`에 세고 다음 기기에 계속 보냅니다. `INVALID_ARGUMENT`는 페이로드 오류일 수 있어 삭제하지 않습니다. 명시적 재시도는 없습니다.

수동 요청 모음은 [http/notifications.http](http/notifications.http)에 있습니다.

## GHCR 이미지와 로컬 컨테이너

`Docker Image` 워크플로는 dev 대상 PR에서 테스트와 이미지 빌드만 수행합니다. dev push에서는 테스트 후 `ghcr.io/kim-n-kang/manyak-notification:<short-sha>`와 `:dev`를 함께 올립니다. 플랫폼은 `linux/amd64,linux/arm64`입니다. 수동 실행은 테스트와 빌드만 하며 이미지를 올리지 않습니다. ECR, AWS 인증, ECS 배포는 없습니다.

PR 머지 후 이미지가 게시되면 세 환경변수를 설정한 터미널에서 실행합니다. 아래 서버 주소는 Docker Desktop에서 호스트의 서버 8080에 연결하는 예입니다. Linux에서는 호스트 주소를 환경에 맞게 바꿉니다.

```sh
export MANYAK_SERVER_INTERNAL_BASE_URL=http://host.docker.internal:8080
# 나머지 두 환경변수도 위 설정 표에 따라 주입

docker pull ghcr.io/kim-n-kang/manyak-notification:dev
docker run --rm --name manyak-notification -p 8081:8080 \
  -e MANYAK_INTERNAL_SHARED_SECRET \
  -e MANYAK_SERVER_INTERNAL_BASE_URL \
  -e MANYAK_FCM_SERVICE_ACCOUNT_JSON \
  ghcr.io/kim-n-kang/manyak-notification:dev
```

이미지가 비공개이면 GHCR 읽기 권한으로 먼저 로그인합니다. 다른 터미널에서 앞의 헬스체크 및 시크릿 없는 POST 명령으로 200/UP과 401을 확인합니다. 세 환경변수가 빈 경우의 동작은 설정 표와 같습니다.

## 상관 헤더와 로그

`X-Manyak-Request-Id`는 응답에 그대로 돌려주고 없거나 공백이면 `req_` 접두 UUID를 생성합니다. `X-Manyak-Session-Id`, `X-Manyak-Device-Id-Hash`는 받은 값을 MDC에 넣으며 없으면 `unknown`입니다. 서버 API 호출에는 MDC의 세 값을 전달하되 빈 값과 `unknown`은 생략합니다. 원본 기기 ID를 받거나 해시를 계산하지 않습니다.

기본 로컬 로그는 사람이 읽는 형식이고 `dev`, `prod`, `jsonlog` 프로파일에서는 JSON 한 줄입니다. 요청 처리 로그에 MDC 상관 식별자를 포함합니다. 기본 INFO에서는 헬스체크 요청 로그를 남기지 않습니다. 상관 헤더 확인은 `LOGGING_LEVEL_COM_KNK_MANYAK_NOTIFICATION_GLOBAL_OBSERVABILITY=DEBUG`로 실행합니다.

메트릭 `manyak.push.send.result{outcome=success|unregistered|failure}`를 기동 시 0으로 사전 등록합니다.
