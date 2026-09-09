# pipeline-v2-site — 분석 파이프라인 v2 클릭형 아키텍처 문서

정적 HTML 열 장 + 공용 CSS + 흐름도 렌더러(`diagram.js`). 서버·빌드 없이 `index.html` 을 브라우저로 열면 된다.

```bash
open docs/architecture/pipeline-v2-site/index.html
```

## 페이지

| 파일 | 번호 | 내용 |
|---|---|---|
| `index.html` | 1 | 업로드·분석·폴더 생성의 3단계 지도. 컴포넌트별 상세 연결도는 펼쳐서 확인. 박스 클릭 → 상세 |
| `upload.html` | 2 | 브라우저 워커 리사이즈·EXIF·CRC32C, 발급·서명·complete, PENDING 보정, 진행 요약 |
| `embed.html` | 3 | 관리자 사진 교체(ExactPhoto)의 공용 포트 경로 포함.  wes `EmbedDispatcher` 배정 규칙·SQL·StageInvoker. embedder 박스 → 3.1 |
| `embedder.html` | 3.1 | AI repo embedder Lambda: 프리페치, 축소 디코드, previews PUT → DINOv3, EXIF, UPSERT |
| `score.html` | 4 | wes `ScoreWorkerSupervisor` 켜기·끄기·폴백 규칙, `Ec2ScoreWorkerPool`. score 박스 → 4.1 |
| `score-worker.html` | 4.1 | AI repo score: CLIP·LAION·ARNIQA·피사체·부모 프롬프트, 집기 SQL, 자기 정지, Lambda 폴백 |
| `categorize.html` | 5 | wes 잡 상태 기계: 잡 생성 게이트, stepAnalyzing/Categorizing, CAS, 타임아웃. categorize 박스 → 5.1 |
| `categorize-lambda.html` | 5.1 | AI repo categorize: 백분위·연사·계층 클러스터, naming 6단계, 시스템 프롬프트 원문 |
| `entities.html` | 사전 | photos · photo_analysis · ai_analysis_jobs · ai_concept_assignments · concept_folders · detail_folders · photo_category_assignments 모든 컬럼의 뜻·쓰는 주체·채워지는 시점. 다이어그램 없음 |
| `folders.html` | 6 | wes `AiCategoryFolderService` 물질화: 진입점, **값의 행선지**(categorize 가 채운 컬럼 → 관측 → 폴더 필드 흐름도·매핑표), 알고리즘, 용어 뒤집힘, 칩 다수결, USER 배정 보호 |

## 편집 방법

### 흐름도 (`diagram.js`)

각 페이지의 아키텍처 그림은 SVG 를 손으로 그린 것이 아니라, 페이지 하단 `<script>` 의 **노드·엣지 목록**을 `diagram.js` 가 그린 것이다.
좌표는 노드에만 적고, 화살표는 직각 경로를 자동으로 만든다. 브라우저에서 파일을 열어 새로고침하면 바로 반영된다.

```js
renderDiagram('#d-arch', {
  width: 1000, height: 600,                       // viewBox. 읽기 크기 / 전체 보기로 배율 조절
  groups: [{ x, y, w, h, label, color, tint }],    // 단계별 색상 테두리와 옅은 배경
  nodes: [
    { id: 'W1', lane: 'wes', x: 240, y: 60, w: 220,
      kicker: 'photo', title: '업로드 URL 발급',   // 역할을 먼저 보여주고 기술명은 툴팁에 보존
      technicalTitle: 'PhotoService.issueUploadUrls',
      lines: ['행 하나', '행 둘'],                  // 상자 안 알약 행
      href: 'upload.html#issue' },                 // 있으면 클릭 가능(↗)
    { id: 'PG', lane: 'db', shape: 'db', x: 800, y: 300, w: 180, h: 200, title: 'Postgres', lines: [...] },
  ],
  edges: [
    { from: 'W1', to: 'PG', label: 'INSERT' },                           // 기본: W1 오른쪽 → PG 왼쪽
    { from: 'B1', to: 'S3', fromSide: 't', toSide: 't',                  // 변: l r t b, at 은 0~1 위치
      via: [[105, 58], [890, 58]], label: 'PUT', labelAt: 1,             // 경유점. null 은 "직전 점과 같은 값"
      dashed: true, color: 'browser', bidir: true },
  ],
});
```

- `lane`/`color`: `wes`(초록) · `lambda`(블루그레이) · `gpu`(앰버) · `browser`(보라) · `db`/`s3`(회색) · `warn`(주황).
- `shape`: 기본 상자 · `db`(원통) · `actor`(사람). 상자 높이는 내용으로 계산되며 `h` 로 고정할 수 있다.
- 화살표 라벨은 `labelAt`(세그먼트 번호, 0부터)로 놓을 선분을 고르고 `labelDx`/`labelDy` 로 밀어 겹침을 푼다. `\n` 은 줄바꿈.
- 열 사이 간격은 라벨 폭보다 넓게(보통 120px 이상). 노드를 옮기면 그 노드에 붙은 화살표는 따라오지만 `via` 경유점은 절대 좌표라 함께 고쳐야 한다.
- 실선 = 호출·쓰기, 점선(`dashed`) = 읽기·관측. 상세 옵션은 `diagram.js` 머리말 주석.
- 도면은 기본적으로 상세 125%, 전체 지도 100%로 표시한다. 좁은 화면에서는 도면 안에서 스크롤한다. `전체 보기`는 화면 폭에 맞추고, `읽기 크기`는 기본 배율로 돌아온다.
- `large: true`는 전체 지도용 큰 글자·행 간격을 사용한다. `title`은 도면의 접근성 이름이다. 그룹의 `color`/`tint`는 단계 색상이며, 노드 색상은 실행 주체를 구분한다.
- 전체 지도는 초록(업로드)·노랑(분석)·분홍(정리) 영역을 쓴다. 함수 호출 인자·SQL·재시도 상세는 기존 연결도와 하단 설명에 둔다. 설명 문구를 바꿀 때 실행 주체나 호출 관계를 바꾸지 않는다.
- 전체 지도의 상세 연결도는 처음 펼칠 때 렌더링한다. 숨겨진 SVG의 텍스트 크기를 잘못 측정하지 않도록 이 방식을 유지한다.

### 나머지 요소

- 표는 `<div class="tablewrap"><table>` 로 감싼다(좁은 화면 가로 스크롤). 셀 안 코드가 길면 `<td class="wrap">`.
- 번호 단계는 `<ol class="steps">`, 콜아웃은 `<div class="note">` / `<div class="note warn">`, 데이터 요약 띠는 `.datastrip > .cell`, 상태 기계는 `.states`.
- 간단한 일렬 흐름에는 HTML 박스 `.flow`(가로) / `.flow.vertical`(세로)도 남아 있다 — `.node` 사이 화살표는 CSS 가 그린다.
- 흰 배경과 산세리프 글꼴을 사용한다. 도면의 단계 색상과 대비를 유지하도록 각 HTML의 `data-theme="light"`로 밝은 테마를 고정한다. 외부 웹폰트를 불러오지 못하면 시스템 한글 글꼴로 표시한다.
- 페이지 상단 `.nav` 와 하단 `.pager` 링크는 손으로 관리한다. 새 페이지를 추가하면 `index.html` 의 "페이지 지도"(`.map`)에도 한 줄 넣는다.

## 사실의 출처

최초 작성은 2026-09-09의 세 저장소 코드 기준이다(AI repo b473c61, web repo 당시 main). 서버 연동은 원격 wes main@1503a9a로 재확인했으며, #177(8daf4c6)·#179(1503a9a)의 변경을 반영했다. 계획서(`docs/plans/pipeline-v2-wes.md`)와 코드가 다른 곳은 코드를 따르고 페이지 안에 표시했다. 코드가 바뀌면 해당 페이지의 "코드 위치" 표와 상수 표부터 고친다.


## 서버 구조 기준 (#177 · #179)

- 외부 실행 포트: `analysis/service/port/StageInvoker`, `ScoreWorkerPool`.
- GPU 제어 서비스: `ScoreWorkerSupervisor`. score Lambda 배치는 `app.analysis.score-batch-size`(기본 50)로 독립 설정한다.
- 내부 DTO: `StageCallDto`, `OrchestratorActionDto`, `MaterializeOutcomeDto`, `LatestAssignmentsDto`, `ScoreWorkerDto`, `ScoreWorkerStateDto`.
- `StageCallDto`는 Embed·Score·Categorize·ExactPhoto 네 타입이다. 운영은 ObjectMapper 직렬화 후 EVENT 호출, ExactPhoto는 일반 Embed와 같은 Lambda 함수로 전달한다. 로컬 ExactPhoto는 미지원이다.
- 관리자 단건 교체 흐름은 `embed.html#exact-photo`, 실행기 측 설명은 `embedder.html#admin`에 둔다. 일반 배치에 관리자 잡 키를 섞지 않는다.
