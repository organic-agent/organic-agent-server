# select-layer-site — 셀렉 계층(사람이 쓰는 기능) 클릭형 아키텍처 문서

정적 HTML 일곱 장 + 공용 CSS + 흐름도 렌더러(`diagram.js`). `index.html` 을 브라우저로 열면 된다. 형식은 `../pipeline-v2-site/`·`../ai-recommendation-site/` 와 같다.
업로드·분석 파이프라인(pipeline-v2-site)과 AI 폴더 추천(ai-recommendation-site)은 범위 밖.

```bash
open docs/architecture/select-layer-site/index.html
```

## 페이지

| 파일 | 번호 | 내용 |
|---|---|---|
| `index.html` | — | 큰 그림(작가·부부·하객 → 인가 → 다섯 기능 → 저장소·Bedrock), 한 갤러리의 여정, 기능 한눈에, 원칙 |
| `studio.html` | 0 | 워크스페이스·스튜디오·갤러리·초대 관계, 스튜디오 API, 개인 작가, 갤러리 상태 전이·상한, 부부 초대, GalleryAccessPolicy 전표, 알림 |
| `rating.html` | 1 | photo_ratings 사진당 1행, 노출 규칙(작가·하객 null), minScore 필터, 부부 댓글, AI 접근 금지 |
| `collab.html` | 2 | 세션·모드·참여자, 부부/하객 API, CollabSessionAccess 세 문, 집계, 셀렉 미반영·실시간 없음 |
| `retouch.html` | 3 | 회차·항목·포인트, 엔드포인트, 회차 생명주기, 보정본 S3 직접 업로드, 요청문 정제 |
| `selection.html` | 4 | 담기·빼기·제출·취소·export, 제출이 잠그는 것, 도메인 규칙, 접점 |
| `entities.html` | 사전 | workspaces · workspace_members · studios · studio_invites · galleries · gallery_invites · gallery_members · photo_ratings · photo_comments · collab_* · retouch_* · photo_selections · photo_selection_items |

## 편집 방법

- `site.css`·`diagram.js` 는 `../ai-recommendation-site/` 의 복사본(`spec.fit` 기본 true, 역할 카드 `.role`·`.fn` 포함). 렌더러를 고칠 때는 세 폴더에 함께 복사한다.
- 흐름도는 각 페이지 하단 `<script>` 의 `renderDiagram(...)` 노드·엣지 목록. 규칙(레인 색·`via` 의 null·`labelAt`·열 간격)은 `../pipeline-v2-site/README.md`.
- 각 기능 제목 아래 `<div class="role">` 카드(한다 · 왜 · 없으면 · 입출력)와 `index.html` 의 `.fn` 격자는 손으로 관리한다.

## 사실의 출처

2026-09-09 wes main@45981bf 와 web repo 코드를 직접 읽고 썼다. 비교샷(`ai_pair_verdicts`·`compare.html`)은 기능이 사라져 2026-09-14 에 걷어냈다(#187). 기존 문서와 코드가 다른 곳은 코드를 따랐고 페이지에 표시했다:
`collab-session.md`·`collab-services.md`(votes·share_token·갤러리당 1:1 → 실제는 likes·collab_token·N세션), `RETOUCH_PLAN.md`·`retouch-frontend-guide.md`(포인트 없음·requireCouple → 실제 RetouchPoint jsonb·requireRetouchRequester),
`wes-architecture-2026-08.md`(studio_id·requirePhotographer → workspace_id·requireUploader). 알려진 불일치: `ck_galleries_stage` CHECK 가 DELIVERY 를 불허, 별점 Swagger 의 "작가 허용" 문구.
