---
paths:
  - "src/main/kotlin/**/*.kt"
---

# DTO 컨벤션

`{domain}/dto/request`·`dto/response`가 API 계약의 자리다. 서비스가 양쪽 DTO를 소유한다 —
컨트롤러는 통과시키기만 한다.

## 파일과 이름

- 요청·응답 DTO는 **같은 기능에서 함께 쓰이는 것끼리 한 파일에 둘 수 있다**
  (`folder/dto/request/FolderRequests.kt`의 `CreateConceptFolderRequest`·`CreateDetailFolderRequest`).
  몇 줄짜리 `data class`마다 파일을 만들면 파일만 늘기 때문이다. 묶는 단위는 도메인 전체가 아니라
  기능이다 — 도메인 하나를 한 파일에 몰면 DTO가 늘 때마다 그 파일이 끝없이 길어진다. 파일이
  100~200줄을 넘거나 다른 기능의 DTO가 섞이기 시작하면 기능 단위로 나눈다. 따로 서는 DTO는
  클래스명과 같은 파일에 둔다 (`MoveFolderPhotosRequest.kt`).
- 감싸는 DTO만 쓰는 하위 클래스는 중첩한다 (`IssueUploadUrlsRequest.FileRequest`).
- `dto` 루트의 내부 `~Dto`는 **한 파일에 하나**를 유지한다 — 다른 도메인이 가져다 쓰는 값이라
  파일명으로 찾을 수 있어야 한다.
- 이름은 `{동사/자원}{Request|Response}`. 상세형이 따로 있으면 `{...}DetailResponse`.
- `request`/`response` 하위 패키지는 컨트롤러 경계를 오가는 DTO의 자리다. 그 경계를 지나지 않고
  **service ↔ repository·support·infrastructure 사이에서만 쓰이는 데이터 클래스는 `dto` 루트에
  `~Dto` 접미사로 둔다** (`photo/dto/PresignedUploadDto`). 위치와 접미사가 "API 계약이 아니다"를
  말해주므로, 이 클래스는 바꿔도 클라이언트가 깨지지 않는다.
- **접미사 `Dto`는 `dto` 루트의 모든 클래스에 필수다** — data class뿐 아니라 sealed interface·enum도
  (`AiTaskDto`, `ScoreWorkerStateDto`, `MaterializeOutcomeDto`). 접미사 없는 이름이 섞이면 어느 것이
  API 계약인지 파일명만으로 가릴 수 없다.
- **서비스·support·포트 클래스 안에 데이터 클래스를 중첩하지 않는다.** 결과 묶음, 트랜잭션 밖으로 넘기는
  값, 다른 도메인이 받아 가는 값은 `dto` 루트의 파일 하나다 (`LatestConceptAssignmentsDto`). 허용되는 중첩은
  감싸는 DTO만 쓰는 하위 클래스(위 예외)와 `@ConfigurationProperties`의 중첩 설정(`AnalysisProperties.Gpu`)뿐이다.

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
  같은 규칙이 도메인/서비스에도 있어야 한다 (`FolderErrorCode.EMPTY_PHOTO_IDS`:
  `FolderService.movePhotos`가 빈 목록을 다시 막는다).

## 문서화

- 요청·응답 필드에는 `@field:Schema(description, example)`를 붙인다. description에는 타입 설명이
  아니라 **정책과 사용 흐름**을 적는다 (`MoveFolderPhotosRequest.targetDetailFolderId`: "null이면
  논리적 미분류 상태로 옮긴다").

## 조립

- 응답 DTO는 서비스 또는 support 조립자가 만든다. 정적 팩토리는 companion `of`/`from`.
- 목록 화면의 응답 조립은 배치 메서드를 쓴다 — 단건 조립(`toResponse`)을 루프에서 부르면
  N+1이다 (`PhotoViewAssembler.toResponses`, support.md 참조).
