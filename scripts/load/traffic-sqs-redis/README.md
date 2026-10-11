# traffic-sqs-redis — #286 R-3 도구 (업로드 + 사용자 트래픽 + 장애 주입)

계획: `docs/plans/2026-10-11/traffic-sqs-redis/02-baseline.md`(gitignore). 업로드 쪽은 #274 도구(`../upload-5x-10min/`)를 그대로 쓴다.

| 파일 | 하는 일 | dev 쓰기 |
|---|---|---|
| `prepare.sh dev --label L [--source 20 --galleries 10 --users 400]` | 분석 끝난 갤러리 복제 · 테스트 사용자(`load-user-NNNN`)·멤버 · 토큰 서명 → 버킷 `load-results/L/inputs/` | 예 (갤러리·사용자·멤버) |
| `users.js` | k6 시나리오 — 가상 부부 한 명의 동작(01 QA-1 표). 단계·동작 태그 | (API 쓰기: 별점·담기·빼기) |
| `run-k6.sh dev --label L [--profile full\|smoke] [--start-only] [--report-only]` | 발생기 EC2(`wes-dev-loadgen`)를 켜고 SSM 으로 k6 실행 → 요약 받아 판정 → 끔 | 아니오 |
| `pool.sh dev --label L --since … --until …` | 앱 로그 `event=db.pool`(연결 풀 표본) 분 단위 요약 | 아니오 |
| `fault.sh dev on [--rate 0.02] \| off \| status` | dev 임베더·categorize 의 `FAULT_EXIT_RATE` 켜고 끄기 | Lambda 설정만 |
| `recovery.sh dev --label L --since … --until …` | 주입한 실패마다 다시 처리 끝까지 걸린 시간 · 잃은 것 | 아니오 |
| `cleanup.sh dev --label L [--users]` | 회차 갤러리 삭제 + VACUUM (사용자는 기본 남김) | 예 |

## 회차 순서 (02 §4)

1. 터널 `scripts/db-tunnel.sh dev` · 앱 표본 켜기(SSM `/wes/dev/app.observability.db-pool.enabled=true` → 앱 재시작)
2. `precheck.sh dev` → `top-queries.sh dev --reset` → `upload-5x-10min/sample-db.sh dev --label L-db`(백그라운드)
3. `prepare.sh dev --label L` → `run-k6.sh dev --label L --start-only`(발생기 미리 켬)
4. 같은 순간에: `upload-5x-10min/replay.sh --label L --count 5 …` 와 `run-k6.sh dev --label L --profile full`
5. 끝나면 `timeline.sh`·`cost.sh`·`top-queries.sh`·`pool.sh` → `cleanup.sh` + 업로드 갤러리 정리

장애 주입 회차(R-3-b)는 3·4의 k6 대신 `fault.sh dev on` → 재생 → `recovery.sh` → `fault.sh dev off`.
