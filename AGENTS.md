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

- **dev push는 홈서버 dev 스택에 반영**됩니다(KNK-2125, manyak-terraform `homeserver/dev`).
  - 배포 잡(`deploy-dev`)이 최신 SHA 게이트 뒤에서 GHCR `:<sha7>`를 `:dev`로 옮깁니다. 배포 명령은 보내지 않습니다.
  - 홈서버 updater가 1분마다 `:dev` 다이제스트를 확인해 바뀌었으면 pull하고 컨테이너를 다시 만듭니다. 교체하는 동안 잠깐 멈춥니다(무중단 아님).
  - dev notification은 홈서버의 Kafka(`push.requested` 토픽)를 소비합니다(`MANYAK_PUSH_BROKER=kafka`). 운영은 SQS(`sqs`)입니다. dev EC2, SSM 배포, SQS, dev ECR 경로는 없습니다.
  - dev 환경 변수는 manyak-terraform `homeserver/dev/compose.yaml`에, 비밀값은 홈서버의 `secrets/notification.env`(레포에 없음)에 있습니다.
  - 되돌리기는 홈서버 compose에서 notification `image`를 불변 태그(`:<sha7>`)로 바꾸고 `docker compose up -d notification`을 실행합니다.
- **main push는 운영 EC2 앱 호스트 한 대(태그 `manyak:host=manyak-prod-host`, KNK-2106)에 배포**됩니다.
  - 빌드 잡이 ECR에 `:<sha7>`를 push합니다. 배포 잡(`deploy-prod`)은 최신 main SHA 게이트 뒤에서 SSM 문서 `manyak-prod-host-deploy-notification`으로 호스트 `deploy.sh`를 부릅니다.
  - notification은 들어오는 요청이 없어 프록시를 거치지 않습니다. 새 컨테이너의 Docker 헬스가 healthy가 되면 옛 컨테이너를 내리고, 실패하면 새 컨테이너만 지웁니다(옛 notification이 계속 SQS를 소비). 출력의 `notification: deployed <image>` 줄로 배포를 확인합니다.
  - 배포가 끝난 뒤에만 `:latest`를 그 SHA로 옮깁니다.
  - 되돌리기는 같은 SSM 문서를 `image=rollback`(직전 이미지, 호스트 `/data/state` 기록) 또는 `image=<ECR 이미지>:<sha7>`로 실행합니다.
  - 운영 환경 변수와 시크릿은 manyak-terraform `envs/prod/app-host.tf`의 `app_host_defs`가 관리합니다. 바꾼 뒤에는 SSM 문서 `image=current`로 다시 배포해야 반영됩니다.
