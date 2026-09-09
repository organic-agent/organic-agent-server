# ai-recommendation-site — AI 폴더 추천 클릭형 아키텍처 문서

정적 HTML 일곱 장 + 공용 CSS + 흐름도 렌더러(`diagram.js`). 서버·빌드 없이 `index.html` 을 브라우저로 열면 된다.
형식은 `../pipeline-v2-site/` 와 같다. 비교샷(`ai_pair_verdicts`)은 범위 밖.

```bash
open docs/architecture/ai-recommendation-site/index.html
```

## 페이지

| 파일 | 번호 | 내용 |
|---|---|---|
| `index.html` | 1 | 큰 그림(브라우저 → wes API/실행기 → Runner 7단계 → 저장소·Bedrock). 박스 클릭 → 상세. 상태·화면 대응표, 원칙 |
| `request.html` | 2 | POST/GET 두 엔드포인트, `request()` 게이트 7단계, `list()` 조립, 프론트 폴링·버튼 흐름, 오류 코드 |
| `job.html` | 3 | Launcher·스레드 풀·claim·30초 스윕·기동 복구, `run()` 골격, round 기반 복구, `result` 키 |
| `query.html` | 4 | 자연어 질의 해석(`RecommendationQueryInterpreter`): 입력·스키마·검증 규칙·시스템 프롬프트 원문 |
| `plan.html` | 5 | world 로딩, FolderFitRule, RecommendationScoring 산식, FolderQuota/ExactQuota, MmrSelector, persistRound·score_breakdown |
| `reasons.html` | 6 | ReasonMaterial → ReasonPrompt(원문) → ReasonGenerator → Bedrock 어댑터·설정 `app.llm.*` |
| `entities.html` | 사전 | ai_selection_jobs · ai_recommendations · photo_selections · photo_selection_items · FolderSetDetailDto 의 모든 필드 |

## 편집 방법

- `site.css`·`diagram.js` 는 `../pipeline-v2-site/` 의 복사본이다. 이 폴더의 `diagram.js` 만 `spec.fit`(기본 true — 처음에 페이지 폭에 맞춰 축소)을 추가했다.
  두 폴더의 렌더러를 함께 고칠 때는 양쪽에 복사한다.
- 흐름도는 각 페이지 하단 `<script>` 의 `renderDiagram(...)` 노드·엣지 목록이다. 규칙(레인 색·`via` 의 null·`labelAt`·열 간격 ≥120px)은 `../pipeline-v2-site/README.md` 와 같다.
- 페이지 상단 `.nav` 와 하단 `.pager`, `index.html` 의 페이지 지도는 손으로 관리한다.

## 사실의 출처

2026-09-09 wes main@45981bf 와 web repo 코드를 직접 읽고 썼다(`recommendation/**`, `category/support/AiCategoryFolderSetReader`, `selection/domain`, web `src/lib/api/recommendations.ts`·`src/lib/folderRecommendations.ts`).
코드가 바뀌면 각 페이지의 "코드 위치" 표와 상수 표부터 고친다. 주의할 사실: 반응(거절) API 는 아직 없고 `rejected_at` 은 읽기만 한다 · 설정은 `app.recommendation.*` 이 아니라 `app.llm.*` 하나다 · web 은 `prompt`·`targetCount` 를 보내지 않는다.
