# manyak-notification 작업 지침

이 파일이 이 레포 지침의 정본입니다. Claude Code와 Codex 모두 이 파일을 읽습니다(`CLAUDE.md`는 두지 않습니다). 지침을 바꿀 때는 이 파일만 고칩니다.

## 기본

- 공통 지침은 `../knk-harness/AGENTS.md`를 확인합니다. 하네스와 manyak-server는 참조만 합니다.
- 스펙 정본은 `../knk-harness/docs/spec/NOTIFICATION/BACKEND/`입니다. 문서 역할과 이 서비스가 맡는 범위는 그 세트의 `AGENTS.md`가 안내합니다. 스펙에 없는 계약을 임의로 추가하지 않습니다.
- Kotlin 2.3.21, Spring Boot 4.1.2, Java 25를 사용합니다. Jackson databind와 Kotlin 모듈은 `tools.jackson.*`입니다.

## 브랜치, 커밋, PR

- 기본 브랜치는 `dev`입니다. 작업 브랜치는 `git fetch origin` 후 최신 `origin/dev`에서 만듭니다.
- 커밋과 PR 규칙은 manyak-server와 같습니다. 커밋 제목은 `[KNK-번호] 태그: 한국어 설명`으로 쓰고 Co-Authored-By 트레일러는 금지합니다.
- `git add -- <명시 경로>`만 사용합니다. `git add .`, `git add -A`는 금지합니다.
- PR 본문의 Jira 번호는 `[KNK-번호](https://kimandkang.atlassian.net/browse/KNK-번호)` 링크로 씁니다. PR 생성 후 본인을 assignee로 지정합니다.
- main과 dev에 직접 push하지 않습니다. 최초 골격 부트스트랩만 승인된 예외입니다.

## 검증

- 변경 후 `./gradlew test`로 검증합니다. FCM, 발송 API, DB, 큐는 해당 작업에서 필요할 때 추가합니다.

## 배포

배포는 `.github/workflows/docker-image.yml`이 합니다.

- **dev push는 dev 환경에 반영**됩니다. dev 브랜치에 머지하면 배포 잡(`deploy-dev`)이 최신 SHA 게이트 뒤에서 GHCR `:<sha7>`를 `:dev`로 옮기고, dev 환경이 그 이미지를 자동으로 받아 교체합니다. dev 서버 주소는 `dev-api.manyak.app`입니다.
  - notification은 dev와 운영 모두 Kafka(`push.requested` 토픽)를 소비합니다(`MANYAK_PUSH_BROKER=kafka`).
  - 옛 dev EC2, SSM 배포, SQS, dev ECR 경로는 없습니다.
- **main push는 운영에 배포**됩니다. 운영은 OCI VM 한 대이고, 호스트 구성과 설정의 정본은 manyak-terraform `oci/prod`입니다(KNK-2126).
  - 빌드 잡이 GHCR `ghcr.io/kim-n-kang/manyak-notification:<sha7>`를 push합니다. 배포 잡(`deploy-prod`)은 최신 main SHA 게이트 뒤에서 GHCR `:prod`를 그 SHA로 옮기기만 합니다. CI는 호스트에 들어가지 않습니다.
  - 호스트의 updater(`oci/prod/scripts/update.sh`, 1분 주기)가 `:prod` 다이제스트가 바뀐 것을 보고 `deploy.sh`로 배포합니다. notification은 들어오는 요청이 없어 프록시를 거치지 않습니다. 새 컨테이너의 Docker 헬스가 healthy가 되면 옛 컨테이너를 내리고, 실패하면 새 컨테이너만 지웁니다(옛 notification이 계속 소비하고, 그 다이제스트는 다시 시도하지 않음).
  - notification은 공개 주소가 없어 배포 잡이 교체를 직접 보지 못합니다. 3분 기다린 뒤 `api.manyak.app/actuator/health`를 확인하고, 실패하면 `:prod`를 직전 다이제스트로 되돌립니다. 교체 성공과 실패는 updater 알림과 운영 로그로 봅니다.
  - 되돌리기는 수동 워크플로 `prod-rollback.yml`(입력 `sha`, main 커밋만)로 `:prod`를 직전 릴리스 커밋으로 옮깁니다.
  - 운영 환경 변수는 manyak-terraform `oci/prod/services/notification.env`, 비밀값은 호스트 `/opt/manyak/secrets/notification.env`에 있습니다. 바꾼 뒤에는 호스트에서 `deploy.sh sync`와 `deploy.sh notification current`로 반영합니다.
