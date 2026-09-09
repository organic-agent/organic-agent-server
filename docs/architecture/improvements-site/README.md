# improvements-site — 개선 이력 · 비용 클릭형 문서

정적 HTML 다섯 장 + 공용 CSS + 흐름도 렌더러(`diagram.js`). 서버·빌드 없이 `index.html` 을 브라우저로 열면 된다.
형식과 편집법은 `../pipeline-v2-site/README.md` 와 같다(`site.css`·`diagram.js` 는 그쪽에서 복사한 것).

```bash
open docs/architecture/improvements-site/index.html
```

`pipeline-v2-site`·`infra-site` 가 **현재 구조**를 설명한다면, 이 사이트는 **그 구조가 어떻게 됐는지(개선 이력)** 와 **그 구조로 운영하면 얼마인지(비용)** 를 담는다.
지금은 AI 파이프라인 개선 세 건이 들어 있고, 다른 영역의 개선이 쌓이면 같은 형식으로 페이지를 추가한다.

## 페이지

| 파일 | 번호 | 내용 |
|---|---|---|
| `index.html` | 1 | 세 시대(v1 → 샤딩 → v2) 표 · 시대별 흐름도(Lambda 체인 vs wes 스윕 + GPU pull) · 한 표로 보는 이력(7,189장) · 비용 한눈에 · 읽을 때 주의 · 정본 문서 |
| `sharding.html` | 2 | **삭제된 중간 단계.** v1 실측(822장 22.7분 미완주, 7,189장 100분+ 미완주 — 77.5분은 업로드 63.5 + 32 샤드 분석 14.0 조합) · 조정자 + 샤드 N 구조·손잡이 · 결과 18.3분 · GB-초 비용이 같았던 이유 · 세 벽 · 이것을 대체한 v2 배정 코드 표 |
| `resize-gpu.html` | 3 | 2048 리사이즈(업로드·embedder)와 GPU 워커(score만)의 영향 범위 분리 · pull 구조 도면 · 운영 실측(장당 0.04s, $0.13) · 벤치마크·월 비용 · 알려진 회귀 둘 |
| `categorize.html` | 4 | #108 갤러리 한 번 읽기·대표 사진 배치+병렬 전후 도면 · 09-09 1차 실측 70초(개선 없음)와 시간 분해 · 같은 실행의 v2 체인 8분 21초·$0.38 · 재측정 전 할 일 |
| `cost.html` | 5 | 돈이 나가는 자리 도면 · 단가표 · ① 파이프라인 7,200장 ≈ $0.45 ② 추천 장당 $0.011 ③ S3·인프라 고정비 $145 ④ 월 10/30/100 갤러리 시나리오 · 비용 사고 상한 |

## 사실의 출처

2026-09-10 작성. 수치는 AI repo `docs/improvements/{lambda-pipeline-speedup,score-speedup}-2026-09-06.md`·`gpu-transition-summary-2026-09-07.md`·`lambda-to-gpu-worker-2026-09-08.md`,
`docs/experiments/{upload-timing-2026-09-06,gpu-benchmark-2026-09-07}.md`, wes `docs/improvements/score-lambda-vs-gpu-2026-09-08.md`·`docs/experiments/ai-recommendation-timing-2026-09-06.md`,
인프라 repo `NEXT.md` §4.7·`docs/runbook.md` "비용"·PR #64 브랜치(`fa4795b`)에서 그대로 옮겼다. 09-09 갤러리 13 체인 실측은 AI repo 세션 핸드오프에서 왔다.

비용 페이지의 단가는 AWS ap-northeast-2 온디맨드 목록가와 Anthropic 목록가(Sonnet 4.6 $3/$15 per MTok)다. 실측이 없는 항목(추천 질의 해석 토큰, 열람 전송량,
미리보기 실제 크기, CloudWatch)은 페이지 안에 `추정` 표시가 있다. 단가·구조가 바뀌면 `cost.html` 의 단가표와 `index.html` 의 "비용 한눈에" 띠를 같이 고친다.

## 갱신 트리거

- categorize #108 재측정(09-10 예정) 결과 → `categorize.html` §측정, `index.html` 이력 표의 categorize 행.
- infra PR #64 머지 → `resize-gpu.html`·`index.html` 의 "브랜치에서 인용" 문구 제거.
- 추천 질의 해석 토큰을 로그에서 재면 → `cost.html` ②의 `추정` 제거.
