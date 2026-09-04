---
paths:
  - "src/main/kotlin/**/*.kt"
---

# DTO 컨벤션

`{domain}/dto/request`·`dto/response`가 API 계약의 자리다. 서비스가 양쪽 DTO를 소유한다 —
컨트롤러는 통과시키기만 한다.

## 파일과 이름

- **한 파일에 DTO 하나**, 파일명은 클래스명 그대로. 유일한 예외는 감싸는 DTO만 쓰는 중첩 클래스다
  (`IssueUploadUrlsRequest.FileRequest`).
- 이름은 `{동사/자원}{Request|Response}`. 상세형이 따로 있으면 `{...}DetailResponse`.
- `request`/`response` 하위 패키지는 컨트롤러 경계를 오가는 DTO의 자리다. 그 경계를 지나지 않고
  **service ↔ repository·support·infrastructure 사이에서만 쓰이는 데이터 클래스는 `dto` 루트에
  `~Dto` 접미사로 둔다** (`photo/dto/PresignedUploadDto`). 위치와 접미사가 "API 계약이 아니다"를
  말해주므로, 이 클래스는 바꿔도 클라이언트가 깨지지 않는다.

## 형태

- 전부 `data class` + `val`. 선택 필드는 기본값으로 표현한다
  (`val targetDetailFolderId: Long? = null`).
- 상수는 도메인 것을 참조하고 리터럴을 복제하지 않는다
  (`@field:Size(max = CollabSession.MAX_NAME_LENGTH)`).
- 값객체는 응답에서 원시값으로 풀어 내보낸다 (`PhotoMetadata`의 필드를 낱개로).
  DTO가 `@Embeddable`을 그대로 노출하지 않는다.

## 검증

- 요청 DTO에는 `@field:NotBlank`·`@field:Size` 등 bean validation을 붙인다 — 컨트롤러 경계에서
  좋은 400을 주기 위해서다.
- **단, 애노테이션이 규칙의 유일한 방어여선 안 된다.** `@Valid`는 컨트롤러를 지날 때만 돈다.
  같은 규칙이 도메인/서비스에도 있어야 한다 (`CategoryErrorCode.EMPTY_PHOTO_IDS`:
  `CategoryService.movePhotos`가 빈 목록을 다시 막는다).

## 문서화

- 요청·응답 필드에는 `@field:Schema(description, example)`를 붙인다. description에는 타입 설명이
  아니라 **정책과 사용 흐름**을 적는다 (`MoveCategoryPhotosRequest.targetDetailFolderId`: "null이면
  논리적 미분류 상태로 옮긴다").

## 조립

- 응답 DTO는 서비스 또는 support 조립자가 만든다. 정적 팩토리는 companion `of`/`from`.
- 목록 화면의 응답 조립은 배치 메서드를 쓴다 — 단건 조립(`toResponse`)을 루프에서 부르면
  N+1이다 (`PhotoViewAssembler.toResponses`, support.md 참조).
