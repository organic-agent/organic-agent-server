# availability-2-servers — 배포·장애 때 몇 초 끊기나 재는 도구

이슈 #313(작업 유형 B7, 품질 속성 = 가용성)의 기준선·검증 회차 전용 스크립트다. 계획 문서는 작성자 로컬 `docs/plans/2026-10-15/availability-2-servers/`(02-baseline).
사용자 트래픽은 #286 도구(`../traffic-sqs-redis/users.js` · `run-k6.sh`)를 그대로 쓰고, 여기에는 자극과 초 단위 판정만 둔다.

| 파일 | 하는 일 | 쓰기 |
|---|---|---|
| `resign.sh dev --label L --from B-1-vc` | 이미 준비한 회차의 사용자·갤러리로 토큰만 새로 서명(3시간)하고 지금 `users.js`를 올린다 — 복제 없음 | dev 버킷 `load-results/L/inputs/`만 |
| `../traffic-sqs-redis/run-k6.sh dev --label L --profile steady --csv` | 200명 10분 일정 부하 · 요청마다 시각·상태를 `requests.csv.gz`로 | 없음(API 쓰기는 k6 가 한다) |
| `stimulus.sh dev deploy\|kill\|watch --label L [--delay 초] [--target follower\|leader\|i-…] [--duration 초]` | 부하 중에 진짜 dev 배포(`cd-dev.yml` = 서버마다 한 대씩) · 한 대의 앱 JVM `kill -9`(기본 리더가 아닌 쪽) · 자극 없이 관찰(A-2m·A-3). 늘 ALB 대상 2초 표본 · 서버별 메모리 30초 표본 · ASG 활동·컨테이너·리더 넘김 사건 · 끝에 서버별 GC 멈춤을 남긴다 | dev 앱 재시작(배포는 같은 커밋 다시, kill 은 ASG 가 서버를 갈 수도) |
| `gap.sh dev --label L` | CSV + 자극 기록 → 첫 실패 F · 회복 T1 · 자극 구간 실패율 · 공백 · 사건 · 대상 이탈 · 서버별 GC · 초별 표(조용한 초는 접음) · 판정 | 없음 |
| `gap.py` | `gap.sh`의 계산 본체 | — |

## 측정 정의 (02 §2)

- 실패 = 응답 없음(status 0) 또는 5xx. 4xx는 업무 응답이라 세지 않는다.
- T0 = 배포: 첫 standby(ALB 에서 뺌)·첫 배포 SSM 명령 중 이른 것 · kill: kill 시각 · watch: 시작. F = T0 − 10초부터 첫 실패. T1 = F 뒤 '성공만 있는 초'가 10초 이어진 시작.
- 자극 끝 = 배포: 실행이 끝난 시각(마지막 서버 slow start 까지) · kill: 대상 그룹이 다 healthy 로 돌아온 뒤 slow start 60초.
- 자극 구간 = [T0 − 10초, max(T1, 자극 끝) + 30초]. 그 실패율이 QA-1(< 0.1%). 공백 = 성공 응답 0인 연속 초(5초 넘는 것 0개가 QA-1).
- 대상 이탈 = ALB 대상 2초 표본에서 healthy → 다른 상태. watch(A-2m)는 0 · GC 최대 멈춤 < 3초 · 실패율 < 1%(ADR-0001 §6).
- kill 한 서버가 다시 뜨기 전에 ALB 가 빼면 ASG 가 그 서버를 통째로 갈 수 있다(10-16 01:39 실측) — 그래서 kill 의 끝은 그 서버가 아니라 대상 그룹 전체로 본다.

## 순서 (회차 A-0 · A-0b, 작성자가 실행)

1. `resign.sh dev --label A-0 --from B-1-vc` (토큰은 3시간 — 회차 직전에)
2. 스모크: `../traffic-sqs-redis/run-k6.sh dev --label A-0 --profile smoke` → 오류율 0%면 B-1-vc 갤러리가 살아 있다(403·404면 `prepare.sh`로 다시 준비)
3. `../traffic-sqs-redis/run-k6.sh dev --label A-0 --start-only` (발생기 미리 켬)
4. **같은 순간에** 두 터미널: `run-k6.sh dev --label A-0 --profile steady --csv` · `stimulus.sh dev deploy --label A-0`
5. `gap.sh dev --label A-0`
6. A-0b는 1~5를 `--label A-0b`, `stimulus.sh dev kill`로. 회차마다 2번(`A-0-2` …).

측정 회차는 작성자가 직접 실행한다(`.claude/rules/load-test.md`). DB 터널은 필요 없다.

## 서버 여러 대 회차 (#313 05 §3 — A-2m · A-1 · A-0′ · A-3)

| 회차 | 부하 | 자극 |
|---|---|---|
| A-2m | `run-k6.sh … --profile steady --csv` | `stimulus.sh dev watch --label A-2m --duration 630` |
| A-1 ×2 | 같음 | `stimulus.sh dev kill --label A-1-1` (리더 아닌 쪽) · `… --label A-1-2 --target leader` |
| A-0′ ×2 | 같음 | `stimulus.sh dev deploy --label A-0′-1` |
| A-3 | `run-k6.sh … --profile step600 --csv` (200 3분 → 600 10분 → 200 5분) | `stimulus.sh dev watch --label A-3 --duration 2400` (줄어드는 것까지 40분) |

`scripts/db-tunnel.sh dev` 는 running 인 `wes-dev-app` 아무 한 대를 지난다 — 그 서버가 kill·standby 돼도 터널은 호스트의 SSM 에이전트라 끊기지 않지만, ASG 가 그 서버를 갈면 끊긴다.
