# upload-5x-10min — 작가 5명 × 7,189장 동시 업로드 측정 도구

이슈 #274(작업 유형 B7)의 기준선·검증 회차 전용 스크립트다. 계획 문서는 작성자 로컬 `docs/plans/2026-10-08/upload-5x-10min/`.
공통 측정 도구(`../precheck.sh` · `../timeline.sh` · `../cost.sh` · `../lib/report.sh`)는 상위 폴더에 있고, 여기에는 이 측정에만 쓰는 것만 둔다.

| 파일 | 하는 일 | 쓰기 |
|---|---|---|
| `dev-ids.sh dev` | 재생에 넣을 dev 스튜디오 워크스페이스·멤버 찾기 | 없음 |
| `make-studio.sh --user-id U` | dev에 스튜디오 워크스페이스가 없을 때 한 번 — 그 사용자가 OWNER인 재생용 스튜디오를 만들고 재생 인자를 찍는다 | dev (스튜디오 1개) |
| `seed-dev.sh --source 48` | 운영 갤러리 48의 도착 시각표를 뽑고, 사진을 dev 버킷 `load-seed/48/`로 서버 쪽 복사 → `docs/experiments/load-seed/48-timetable.json` | dev 버킷 (운영은 읽기만) |
| `replay.sh` | 시각표대로 dev 갤러리 N개에 실제 업로드 API 경로로 사진을 도착시키고 분석 요청 (`--count` · `--stagger` · `--speed` · `--limit`) | dev |
| `sample-db.sh dev` | 측정 중 DB 연결 수 샘플, Ctrl+C로 가드레일(≤ 63) 판정 | 없음 |
| `replay.py` · `venv.sh` | 재생 본체(boto3)와 가상환경(`.venv`, gitignore) | — |

순서: `dev-ids.sh` → (후보가 없으면 `make-studio.sh`) → `seed-dev.sh`(한 번) → `replay.sh --limit 200`(스모크) → 회차(B-1 단건 · B-2 5개 동시 ×2 · B-3 1분 간격 ×2 · B-4 2배속). 각 회차는 `../precheck.sh dev`로 시작 조건을 보고, 끝나면 `replay.sh`가 출력하는 `../timeline.sh` 명령으로 잰다.
측정 회차는 작성자가 직접 실행한다(`.claude/rules/load-test.md`).
