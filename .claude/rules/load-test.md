---
description: 부하·기준선 측정 스크립트와 측정 회차 진행 규칙
paths:
  - "scripts/load/**"
---

# 부하 측정

## 측정 회차는 사용자가 실행한다 (강제)

측정 회차(R0~R5, D5 같은 운영 조회 포함)는 **Claude가 직접 실행하지 않는다.** 결과를 요약해 던지지도 않는다.
사용자가 터미널 출력을 직접 보고 캡처해 산출물로 정리한다. 회차마다 아래 형식으로 명령을 주고 멈춘다.

```
N차 테스트 (회차 ID · 무엇을 · 조건 · 반복)
  준비  = (터널·Docker 등 먼저 띄울 것이 있으면)
  script = scripts/load/timeline.sh remote --label R1-1 --ids 1234 --limit 7min
  볼 곳 = (출력에서 확인할 섹션·판정 줄)
```

- DB 터널(`scripts/db-tunnel.sh`)도 사용자가 다른 터미널에서 띄운다. Claude가 백그라운드로 띄우지 않는다.
- 사용자가 결과를 붙여 주면 그때 해석하고 다음 회차 명령을 준다.
- 스크립트를 만드는 중 **로컬 DB로 동작만** 확인하는 것은 Claude가 해도 된다. 그 출력은 산출물이 아니다.
- 쓰기가 있는 회차(재생·복제 등 운영 DB 변경)는 명령을 주기 전에 영향과 중단 방법을 함께 적는다.

## 출력 형식

새 측정 스크립트는 `scripts/load/lib/report.sh`를 지난다 — 캡처한 화면이 회차마다 같은 모양이어야 한다.

- `report_parse_common` → `report_connect` → `report_begin "측정" "대상"` → `report_section` · `report_sql` · `report_verdict` → `report_end`
- 머리말에 회차 라벨·측정 시각(KST)·환경·커밋·실행한 명령이 찍힌다. `--label`은 계획서 회차 ID(R1-1 등)로 준다.
- remote 조회는 `default_transaction_read_only=on` 세션이다. 쓰기가 필요한 스크립트는 lib의 `report_sql`을 쓰지 말고 따로 연결한다.
- 출력은 `docs/experiments/load-runs/{label}-{시각}.txt`에도 색 없이 저장된다(`LOAD_RUNS_DIR`로 바꿈).
- 판정은 `report_verdict pass|fail|info "기준" "실측"` — 계획서 6장 표의 기준을 그대로 쓴다.
