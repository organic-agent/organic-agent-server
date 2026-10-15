# availability-2-servers — 배포·장애 때 몇 초 끊기나 재는 도구

이슈 #313(작업 유형 B7, 품질 속성 = 가용성)의 기준선·검증 회차 전용 스크립트다. 계획 문서는 작성자 로컬 `docs/plans/2026-10-15/availability-2-servers/`(02-baseline).
사용자 트래픽은 #286 도구(`../traffic-sqs-redis/users.js` · `run-k6.sh`)를 그대로 쓰고, 여기에는 자극과 초 단위 판정만 둔다.

| 파일 | 하는 일 | 쓰기 |
|---|---|---|
| `resign.sh dev --label L --from B-1-vc` | 이미 준비한 회차의 사용자·갤러리로 토큰만 새로 서명(3시간)하고 지금 `users.js`를 올린다 — 복제 없음 | dev 버킷 `load-results/L/inputs/`만 |
| `../traffic-sqs-redis/run-k6.sh dev --label L --profile steady --csv` | 200명 10분 일정 부하 · 요청마다 시각·상태를 `requests.csv.gz`로 | 없음(API 쓰기는 k6 가 한다) |
| `stimulus.sh dev deploy\|kill --label L [--delay 초]` | 부하 중에 진짜 dev 배포(`cd-dev.yml` 수동 실행) 또는 앱 JVM `kill -9`, 그 시각을 `stimulus.env`로 | dev 앱 재시작(배포는 같은 커밋 다시) |
| `gap.sh dev --label L` | CSV + 자극 기록 → 첫 실패 F · 회복 T1 · 자극 구간 실패율 · 공백 · 초별 표 · 판정 | 없음 |
| `gap.py` | `gap.sh`의 계산 본체 | — |

## 측정 정의 (02 §2)

- 실패 = 응답 없음(status 0) 또는 5xx. 4xx는 업무 응답이라 세지 않는다.
- T0 = 배포: 배포 스크립트 SSM 명령 시작 · kill: kill 시각. F = T0 − 10초부터 첫 실패. T1 = F 뒤 '성공만 있는 초'가 10초 이어진 시작.
- 자극 구간 = [T0 − 10초, T1 + 30초]. 그 실패율이 QA-1(< 0.1%). 공백 = 성공 응답 0인 연속 초(5초 넘는 것 0개가 QA-1).

## 순서 (회차 A-0 · A-0b, 작성자가 실행)

1. `resign.sh dev --label A-0 --from B-1-vc` (토큰은 3시간 — 회차 직전에)
2. 스모크: `../traffic-sqs-redis/run-k6.sh dev --label A-0 --profile smoke` → 오류율 0%면 B-1-vc 갤러리가 살아 있다(403·404면 `prepare.sh`로 다시 준비)
3. `../traffic-sqs-redis/run-k6.sh dev --label A-0 --start-only` (발생기 미리 켬)
4. **같은 순간에** 두 터미널: `run-k6.sh dev --label A-0 --profile steady --csv` · `stimulus.sh dev deploy --label A-0`
5. `gap.sh dev --label A-0`
6. A-0b는 1~5를 `--label A-0b`, `stimulus.sh dev kill`로. 회차마다 2번(`A-0-2` …).

측정 회차는 작성자가 직접 실행한다(`.claude/rules/load-test.md`). DB 터널은 필요 없다.
