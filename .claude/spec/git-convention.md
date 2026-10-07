# Git 컨벤션

## 브랜치 전략

작업 브랜치는 `origin/develop`에서 분기해 `develop`으로 PR을 보낸다(squash 머지). 흐름은 `feature → develop → main`이다.

| 브랜치 | 머지되면 | 무엇이 돈다 |
|---|---|---|
| `develop` | dev 서버 배포 (`dev.api.easyselect.kr`, `dev.admin.easyselect.kr`) | `cd-dev.yml` |
| `main` | 운영 배포 | `cd-prod.yml` |

- **운영 반영**: develop → main PR에 `ready-to-merge` 라벨을 붙이면 `ff-merge.yml`이 체크(CI·dev 배포) 통과를 확인하고
  develop의 커밋을 머지 커밋 없이 그대로 main에 올린다(fast-forward). 머지 버튼은 쓰지 않는다 — SHA가 갈라져 다음 FF가 막힌다.
- **hotfix**: `origin/main`에서 분기해 main으로 PR(squash). 머지되면 `sync-develop.yml`이 develop을 main 위로 맞춘다
  (develop에 배포 전 커밋이 없으면 FF, 있으면 rebase 후 force push). 충돌이면 실패로 남으니 손으로 rebase 한다.
- develop이 rebase 되면 그 위에서 딴 로컬 작업 브랜치는 `git rebase --onto origin/develop <옛 develop> <브랜치>`로 옮긴다.

## 커밋 타입 표

형식: `{type}: 커밋 내용(#{이슈번호})`

| 종류 | 설명 | Title 접두사 | 브랜치 접두사 | 이슈 라벨 |
|---|---|---|---|---|
| feat | 새로운 기능 추가 | `feat:` | `feat/` | `feat` |
| fix | 버그 수정 | `fix:` | `fix/` | `fix` |
| refactor | 동작 변경 없는 코드 개선 | `refactor:` | `refactor/` | `refactor` |
| hotfix | 운영 환경 긴급 수정 | `hotfix:` | `hotfix/` | `hotfix` |
| docs | 문서 추가·수정 | `docs:` | `docs/` | `docs` |
| test | 테스트 코드 추가·수정 | `test:` | `test/` | `test` |
| cicd | CI/CD 파이프라인·빌드 설정 | `cicd:` | `cicd/` | `cicd` |
| chore | 그 외 잡무(의존성, 설정 등) | `chore:` | `chore/` | `chore` |

## 브랜치 명명 규칙

`{종류}/{이슈번호}-{slug}` 형식을 사용한다. `slug`는 영문 kebab-case로 작성한 간단한 설명이다.

예: fix 이슈 #420 "로그인 리다이렉트 오류" → `fix/420-login-redirect`
