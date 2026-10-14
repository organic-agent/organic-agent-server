# traffic-sqs-redis — #286 R-3 도구 (업로드 + 사용자 트래픽 + 장애 주입)

계획: `docs/plans/2026-10-11/traffic-sqs-redis/02-baseline.md`(gitignore). 업로드 쪽은 #274 도구(`../upload-5x-10min/`)를 그대로 쓴다.

| 파일 | 하는 일 | dev 쓰기 |
|---|---|---|
| `prepare.sh dev --label L [--source 20 --galleries 10 --users 400] [--workspace-ids …] [--couples]` | 분석 끝난 갤러리 복제 · 테스트 사용자(`load-user-NNNN`)·멤버 · 토큰 서명 → 버킷 `load-results/L/inputs/`. `--workspace-ids` 면 복제 갤러리를 그 스튜디오들에 돌아가며 나누고, `--couples` 면 한 갤러리의 사람들이 k6 에 같이 들어온다 | 예 (갤러리·사용자·멤버) |
| `users.js` | k6 시나리오 — 가상 부부 한 명의 동작(01 QA-1 표). 단계·동작 태그 | (API 쓰기: 별점·담기·빼기) |
| `run-k6.sh dev --label L [--profile full\|smoke] [--intensity 2] [--start-only] [--report-only]` | 발생기 EC2(`wes-dev-loadgen`)를 켜고 SSM 으로 k6 실행 → 요약 받아 판정 → 끔 | 아니오 |
| `pool.sh dev --label L --since … --until …` | 앱 로그 `event=db.pool`(연결 풀 표본) 분 단위 요약 | 아니오 |
| `fault.sh dev on [--rate 0.02] \| off \| status` | dev 임베더·categorize 의 `FAULT_EXIT_RATE` 켜고 끄기 | Lambda 설정만 |
| `recovery.sh dev --label L --since … --until …` | 주입한 실패마다 다시 처리 끝까지 걸린 시간 · 잃은 것 | 아니오 |
| `cleanup.sh dev --label L [--users]` | 회차 갤러리 삭제 + VACUUM (사용자는 기본 남김) | 예 |
| `jfr.sh dev on --label L` · `off` · `status` · `fetch --label L [--since HH:MM --until HH:MM]` | 앱을 JFR 녹화와 함께 다시 띄우고(녹화는 볼륨 `logs/jfr`), 끝나면 원래 설정으로 되돌린 뒤 녹화를 받아 CPU 표본(스레드·라이브러리·기능 입구·우리 코드)·할당·GC 를 요약(`jfr_samples.py`) | 앱 재시작 2번 |

## 회차 순서 (02 §4)

1. 터널 `scripts/db-tunnel.sh dev` · 앱 표본 켜기(SSM `/wes/dev/app.observability.db-pool.enabled=true` → 앱 재시작)
2. `precheck.sh dev` → `top-queries.sh dev --reset` → `upload-5x-10min/sample-db.sh dev --label L-db`(백그라운드)
3. `prepare.sh dev --label L` → `run-k6.sh dev --label L --start-only`(발생기 미리 켬)
4. 같은 순간에: `upload-5x-10min/replay.sh --label L --count 5 …` 와 `run-k6.sh dev --label L --profile full`
5. 끝나면 `timeline.sh`·`cost.sh`·`top-queries.sh`·`pool.sh` → `cleanup.sh` + 업로드 갤러리 정리

장애 주입 회차(R-3-b)는 3·4의 k6 대신 `fault.sh dev on` → 재생 → `recovery.sh` → `fault.sh dev off`.

## 실제에 가까운 대본 — R-3-c (부부 2명 × 갤러리 200개)

R-3-a 대본은 갤러리 10개에 40명씩 몰려 갤러리 행·작업공간 행 경합을 부풀린다. 실제는 갤러리 하나에 부부 2명, 그런 갤러리가 많다.

| | R-3-a | R-3-c |
|---|---|---|
| 셀렉하는 사람 | 갤러리 10개 × 40명(갤러리 20, 7,189장 복제) | **갤러리 200개 × 2명**(갤러리 69, 499장 복제 — 분석 끝난 실제 부부 갤러리) |
| 스튜디오 | 전부 작업공간 1 | **스튜디오 20곳 × 갤러리 10개**, 업로드 작가 5명의 스튜디오(1·6·7·8·9) 포함 |
| 들어오는 순서 | 갤러리마다 한 명씩 돌아가며 | 한 갤러리의 부부가 같이(50명 = 갤러리 25개) |
| 열 때 목록 | 36페이지 | 3페이지 |
| 한 사람 동작(열고 난 뒤) | 상세 5초 · 별점 10초 · 담기/빼기 15초 — 분당 27 · 쓰기 37% | **운영 로그 비율**(갤러리 52 부부 일주일) × **강도 2×**(기본, `--intensity`): 분당 ≈ 24.6 · 쓰기 13% — 1× 빈도 — 목록 5.6 · 셀렉 상태 2.7 · 알림 1.1 · 보정 회차 0.75 · 상세 0.54 · 별점 1.5 · 담기/빼기 0.13 (`users.js` `RATES`) |

```
W=1,6,7,8,9,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25   # dev 측정 스튜디오 20곳(11~25는 10-13 make-studio.sh 로 만듦, OWNER user 1)
scripts/load/traffic-sqs-redis/prepare.sh dev --label R-3-c --source 69 --galleries 200 --users 400 --workspace-ids $W --couples
```

나머지 순서(재생·k6·정리)는 위 "회차 순서"와 같다. 업로드 재생은 그대로 `--workspace-id 1,6,7,8,9`.

### 강도(`--intensity`)별 총 요청 — 열기(한 사람 9회) 제외

| 강도 | 한 사람 분당 | 50명 | 100명 | 200명(판정) | 400명 |
|---|---|---|---|---|---|
| 1× (운영 평균) | 12.3 | 615 | 1,230 | 2,460 (초당 41) | 4,920 |
| **2× (기본)** | 24.6 | 1,230 | 2,460 | **4,920 (초당 82)** | 9,840 |
| 3× | 36.9 | 1,845 | 3,690 | 7,380 (초당 123) | 14,760 |

k6 는 응답이 와야 다음을 보내므로(닫힌 루프) 서버가 느리면 실제 보낸 수가 목표보다 적다 — 판정 때 함께 본다.
