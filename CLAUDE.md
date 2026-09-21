# manyak-notification 작업 지침

- 공통 지침은 `../knk-harness/CLAUDE.md`를 확인합니다. 하네스와 manyak-server는 참조만 합니다.
- 스펙 정본은 `../knk-harness/docs/spec/4-backend-server-spec.md`의 알림 서비스 계약 절입니다. 현재 체크아웃에는 해당 절이 아직 없으므로 계약을 임의로 추가하지 않습니다. 골격 범위는 부팅, 헬스체크, 상관 헤더 처리입니다.
- Kotlin 2.2.21, Spring Boot 4.0.6, Java 21을 사용합니다. Jackson databind와 Kotlin 모듈은 `tools.jackson.*`입니다.
- 기본 브랜치는 `dev`입니다. 작업 브랜치는 `git fetch origin` 후 최신 `origin/dev`에서 만듭니다.
- 커밋과 PR 규칙은 manyak-server와 같습니다. 커밋 제목은 `[KNK-번호] 태그: 한국어 설명`으로 쓰고 Co-Authored-By 트레일러는 금지합니다.
- `git add -- <명시 경로>`만 사용합니다. `git add .`, `git add -A`는 금지합니다.
- PR 본문의 Jira 번호는 `[KNK-번호](https://kimandkang.atlassian.net/browse/KNK-번호)` 링크로 씁니다. PR 생성 후 본인을 assignee로 지정합니다.
- main/dev 직접 push는 하지 않습니다. 최초 골격 부트스트랩만 승인된 예외입니다.
- 변경 후 `./gradlew test`로 검증합니다. FCM, 발송 API, DB, 큐는 해당 작업에서 필요할 때 추가합니다.
