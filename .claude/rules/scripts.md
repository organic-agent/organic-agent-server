---
description: scripts/ 폴더 규격 — 실행 주체별 폴더, 환경 인자, 머리 주석, 언어
paths:
  - "scripts/**"
---

# 스크립트

## 폴더 = 실행 주체

| 폴더 | 누가 실행하나 | 예 |
|---|---|---|
| `deploy/` | CD 파이프라인이 서버·러너에서. 사람·Claude는 직접 실행하지 않는다(hook이 막음) | `deploy-public-ssm.sh` · `rolling-deploy.sh` |
| `ci/` | CI 검사(`ci.yml`) | `check-workflow-run-size.rb` |
| `local/` | 로컬 앱(local 프로필)이 서브프로세스로. 운영 Lambda·GPU 워커의 대역 | `lambda/` · `gpu/` · `local-ai.sh` |
| `dev-tools/` | 사람이 손으로. 환경을 골라 DB·S3를 만지는 도구 | `db-tunnel.sh` · `reset-test-data.sh` |
| `load/` | 사람이 측정 회차로. 규칙은 `load-test.md` | `precheck.sh` · `upload/` · `traffic/` |
| `lib/` | 폴더를 넘는 공용 라이브러리(source 전용) | `env.sh` |

- 새 스크립트는 실행 주체로 폴더를 고른다. `scripts/` 바로 아래에는 파일을 두지 않는다. <!-- SC-01 -->
- `deploy/`는 환경을 인자가 아니라 CD가 넘기는 변수(`PARAMETER_PREFIX` 등)로 받는다. 바꾸면 dev 배포(develop push)로 먼저 확인한다.
- `ci/`는 저장소 파일만 읽는다. 네트워크·AWS를 부르지 않는다. <!-- SC-07 -->
- `local/lambda/` 스크립트 하나 = 운영 Lambda 함수 하나(AI repo 최상위 모듈과 같은 이름)이고, 인자는 Lambda 페이로드 키를 그대로 옮긴다.
  경로는 앱 설정(`app.analysis.local-script-dir`)과 `LocalScoreWorkerPool`이 들고 있어 옮기면 함께 고친다. <!-- SC-08 -->

## 환경 인자

- 환경을 고르는 스크립트(`dev-tools/`·`load/`)는 `local | dev | prod`를 받는다. 운영은 `prod`이고 `remote`는 쓰지 않는다. <!-- SC-02 -->
- 환경 인자는 필수다. 기본값을 두지 않는다 — 명령 줄만 보고 대상을 알 수 있어야 한다. <!-- SC-03 -->
- 환경마다 다른 이름(SSM 프리픽스·터널 포트·리소스 접두사·API 주소·배포 폴더)은 `scripts/lib/env.sh`에만 적는다.
  스크립트는 `env_take`·`env_require`·`env_db_connect`와 그 변수(`SSM_PREFIX`·`TUNNEL_PORT`·`API_BASE`·`APP_DIR` …)를 쓴다.
  `load/` 스크립트는 `load/lib/report.sh`를 거쳐 읽는다. <!-- SC-04 -->
- 두 환경을 일부러 함께 쓰는 스크립트(원본 읽기 → dev 쓰기)는 둘 다 명령 줄로 받는다 — 예: `seed-dev.sh --from prod dev`.
- `prod` 데이터 쓰기는 사용자가 직접 실행한다. hook(`guard_bash.py`)이 데이터 스크립트의 `prod`·`remote`를 막는다. `prod` 조회는 읽기 전용 세션으로만 한다.

## 머리 주석

```bash
#!/usr/bin/env bash
# 손으로 테스트한 데이터를 지우고 처음 상태로 되돌린다.          ← 한 줄 요약
# 대상: local | dev | prod                                      ← 이 스크립트가 받는 환경
# 바꾸는 것: DB(갤러리·사진·폴더) · --with-s3 면 S3 원본·파생본   ← 없으면 `없음(읽기 전용)`
#
#   scripts/dev-tools/reset-test-data.sh dev --with-s3           ← 사용 예 하나 이상
#
# (그 아래는 자유 서술 — 까닭·계획 링크·주의)
```

- 진입점 스크립트(`*.sh`, `ci/`의 `*.rb`)는 맨 위 15줄 안에 `# 대상:`·`# 바꾸는 것:` 두 줄과 사용 예를 하나 이상 둔다.
  `deploy/`는 `대상: CD가 넘김(…)`, `ci/`는 `대상: 없음(저장소 파일만 읽음)`, `local/`은 `대상: local`로 적는다. <!-- SC-05 -->

## 언어

- 진입점은 bash다. 파이썬·js·sql은 bash가 부르는 보조 파일이고, 환경 값(SSM 프리픽스·API 주소)은 bash가 인자로 넘긴다.
  `ci/`만 CI가 ruby·python3를 직접 실행해도 된다. <!-- SC-06 -->
