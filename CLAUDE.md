# manyak-notification 작업 지침

- 공통 지침은 `../knk-harness/AGENTS.md`를 확인합니다. 하네스와 manyak-server는 참조만 합니다.
- 스펙 정본은 `../knk-harness/docs/domain/NOTIFICATION/BACKEND/`입니다. 문서 역할과 이 서비스가 맡는 범위는 그 세트의 `AGENTS.md`가 안내합니다. 스펙에 없는 계약을 임의로 추가하지 않습니다.
- Kotlin 2.3.21, Spring Boot 4.1.2, Java 25를 사용합니다. Jackson databind와 Kotlin 모듈은 `tools.jackson.*`입니다.
- 기본 브랜치는 `dev`입니다. 작업 브랜치는 `git fetch origin` 후 최신 `origin/dev`에서 만듭니다.
- 커밋과 PR 규칙은 manyak-server와 같습니다. 커밋 제목은 `[KNK-번호] 태그: 한국어 설명`으로 쓰고 Co-Authored-By 트레일러는 금지합니다.
- `git add -- <명시 경로>`만 사용합니다. `git add .`, `git add -A`는 금지합니다.
- PR 본문의 Jira 번호는 `[KNK-번호](https://kimandkang.atlassian.net/browse/KNK-번호)` 링크로 씁니다. PR 생성 후 본인을 assignee로 지정합니다.
- main/dev 직접 push는 하지 않습니다. 최초 골격 부트스트랩만 승인된 예외입니다.
- 변경 후 `./gradlew test`로 검증합니다. FCM, 발송 API, DB, 큐는 해당 작업에서 필요할 때 추가합니다.
- dev push는 dev 앱 호스트(EC2 한 대, KNK-2102)에 배포됩니다. 배포 잡이 `:dev`를 승격한 뒤 SSM 문서 `manyak-dev-host-deploy-notification`으로 그 커밋 이미지를 띄웁니다. 호스트 `deploy.sh`는 새 컨테이너의 Docker 헬스가 통과하면 옛 컨테이너를 내리고, 실패하면 새 컨테이너만 지웁니다. dev 환경 변수·시크릿은 manyak-terraform `modules/app-host`가 관리하고, 되돌리기는 같은 문서를 `image=rollback`으로 실행합니다. 운영(main)은 지금처럼 ECS `manyak-prod-notification`입니다.
