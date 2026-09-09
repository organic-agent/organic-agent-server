# preference-layer-site — 선호 가중치 층 클릭형 아키텍처 문서 (목표)

정적 HTML 일곱 장 + 공용 CSS + 흐름도 렌더러(`diagram.js`). 서버·빌드 없이 `index.html` 을 브라우저로 열면 된다.
형식은 `../pipeline-v2-site/`·`../ai-recommendation-site/` 와 같다.

**이웃 문서와 다른 점: 현재 구조가 아니라 앞으로의 개선점·목표를 적은 계획 지도다.** 지금 서비스에 붙어 있는 것은 빈 테이블
`preference_models`(V14) 하나뿐이고, 학습기는 AI repo 에 1단계 코드(#66)로만 있으며 Lambda 배포·wes 호출·추천 점수 연결은
전부 앞으로 할 일이다. 각 페이지 상단에 `목표` 태그를, 그림에서는 아직 없는 코드를 주황(`lane: 'warn'`)·점선으로 표시했다.

```bash
open docs/architecture/preference-layer-site/index.html
```

## 페이지

| 파일 | 번호 | 내용 |
|---|---|---|
| `index.html` | 1 | 큰 그림(부부 선택 → wes → preference Lambda 4단계 → preference_models → 추천 잡). 있는 것·없는 것 표, 기능 요약, 원칙 다섯 |
| `artifact.html` | 2 | 가중치 벡터 1,548개의 구성, 특징 x 자리 순서 계약(pref-v1) 표, 갤러리 평균 빼기, λ 감쇠 |
| `train.html` | 3 | `run_train` 4단계(시험이 학습보다 먼저), 라벨 규칙(양성·음성·형제 제외), 두 강도 L2 로지스틱·블록 분산 보정, Knobs, sanity, 로컬 실행 명령 |
| `evaluate.html` | 4 | dedup 순위의 recall@K strict/cluster·AUC, leave-one-gallery-out, 게이트 3조건, 갤러리 8 + golden 30 실측표 |
| `serving.html` | 5 | **목표**: prior 는 어디서 오나, 내적·융합식, wes 에 붙을 코드 자리(Repository·load 확장·combine), 기존 in-session "pref"(유형 lift)와의 이름 충돌, 트리거 A/B |
| `roadmap.html` | 6 | 단계 의존 그림, 남은 일 목록(infra·AI repo·wes), 알려진 제약 다섯, 열린 결정(n₀·L2·트리거·게이트 실패 뒤 active·AI 초안 되먹임), 끝났다고 볼 조건 |
| `entities.html` | 사전 | preference_models 모든 컬럼, holdout jsonb 키, 읽는 입력 4 테이블의 컬럼, GalleryData, golden xlsx, 환경변수·Knobs, Lambda 페이로드·CLI·결과 |

## 편집 방법

- `site.css`·`diagram.js` 는 `../ai-recommendation-site/` 의 복사본(`spec.fit` 포함). 렌더러를 고칠 때는 세 폴더에 함께 복사한다.
- 흐름도는 각 페이지 하단 `<script>` 의 `renderDiagram(...)` 노드·엣지 목록이다. 규칙(레인 색·`via` 의 null·`labelAt`·열 간격 ≥120px)은 `../pipeline-v2-site/README.md` 와 같다.
  이 폴더만의 약속: **아직 없는 코드는 `lane: 'warn'` 노드 + `color: 'warn', dashed: true` 화살표**, 지금 있는 코드는 평소 레인 색.
- 페이지 상단 `.nav` 와 하단 `.pager`, `index.html` 의 페이지 지도·"있는 것과 없는 것" 표는 손으로 관리한다.
- 2단계가 구현되면 이 문서는 "목표"에서 "현재 구조"로 바뀐다 — `warn` 노드를 평소 레인으로 바꾸고, `index.html` 상단 경고 상자와 각 페이지의 `목표` 태그를 지우고, `docs/README.md` 의 소개 문구를 고친다.

## 사실의 출처

2026-09-09 에 AI repo `preference/`(44bcfda, #66 — `docs/preference-layer.md`·`README.md`·`preference/*.py`)와 wes main@1503a9a
(`V14__preference_models.sql`·`AdminEmbedderPrivilegeContractTest`·`RecommendationScoring`·`AiSelectionJobRunner`·`GalleryService`)를 직접 읽고 썼다.
infra repo 와 AI repo `deploy-lambda.yml` 에 preference 가 없음을 확인했다. 실측 숫자(갤러리 8, golden 30)는 AI repo 문서 §6 을 옮겼다.

주의할 사실: wes 러너의 `prefOn`·`W_PREF`·`result.preferenceOn` 은 이 층이 아니라 in-session 피사체 유형 lift 다 · 러너는 지금 범위 폴더의 DINOv3 만 읽는다(CLIP·갤러리 전체 평균은 확장 필요) ·
`write_model` 은 게이트 통과 때만 이전 active 를 내리고 실패 때는 그대로 둔다 · `photo_selection_items.source` 컬럼은 wes 에 아직 없다(학습기는 있으면 MANUAL 만 읽도록 준비돼 있다).
