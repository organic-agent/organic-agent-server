---
description: Kotlin 소스 코드를 작성하거나 수정할 때 계층과 무관하게 공통으로 적용되는 컨벤션
paths:
  - "src/main/kotlin/**/*.kt"
---

# Common Code Convention

## 레이어 구조

Controller → Service → Repository 순서를 따르고, Service는 support(협력자)와
infrastructure(port를 통해)에 기댈 수 있다. **역방향 의존을 만들지 마라**:
support가 service를, infrastructure가 service·repository를 참조하면 안 된다.
계층별 세부 규칙은 각 계층 파일(controller.md, service.md, …)을 따른다.

## 예외 처리

- `throw {Domain}Exception({Domain}ErrorCode.XXX)` 패턴만 쓴다.
  `require`·`check`·표준 예외를 던지고 나중에 번역하는 방식은 쓰지 않는다.
- 에러코드 형식: `ERROR_NAME(HttpStatus.XXX, "{DOMAIN}_{HTTP상태}_{N}", "한글 메시지")` —
  `ErrorCodeFormatTest`가 형식을 강제하므로 새 enum은 그 테스트 목록에 추가한다.
- 자명하지 않은 에러코드에는 KDoc으로 "언제, 왜"를 남긴다 (`RecommendationErrorCode` 참조).

## 객체 생성

- 외부 입력으로 만드는 엔티티·값객체는 companion의 정적 팩토리(`of`)로만 만들고 검증을 거기 둔다
  (domain.md 참조).
- 검증이 필요 없는 단순 연결 행(`AiConceptAssignment`)과 DTO는 생성자를 직접 써도 된다.
  이때 **인자 2개 이상이면 named argument로 쓴다** — `StageCall.Embed(galleryId = ..., photoIds = ...)`.

## 상수

- 매직넘버·매직스트링을 코드에 직접 쓰지 말고 companion object의 `const val`로 선언하고
  KDoc 한 줄로 출처를 남긴다 (`MAX_DELETE_OBJECTS`: S3 한 요청의 최대 키 수).
- 운영에서 조정할 값은 상수가 아니라 설정으로 뺀다 (`app.storage.*`, `app.trash.retention` —
  `@ConfigurationProperties` 클래스는 해당 도메인의 `config`에).

## 포맷팅

- 파라미터가 길어지면 한 줄에 하나씩, 닫는 괄호는 내어쓰고, **trailing comma를 쓴다**:
  ```kotlin
  fun createDetail(
      galleryId: Long,
      conceptId: Long,
      userId: Long,
      request: CreateDetailFolderRequest,
  ): DetailFolderResponse {
  ```
- 생성자 주입 필드도 같은 형태로 한 줄에 하나씩.
- import는 와일드카드 없이 전부 나열한다.

## 메서드 본문 구성

논리 단계가 바뀌면 빈 줄로 구분해 맥락을 드러낸다. 서비스 유스케이스라면
인가 → 조회·잠금 → 검증 → 저장 → 응답 조립의 단계가 빈 줄로 보여야 한다:

```kotlin
galleryAccessPolicy.requireManager(galleryId, userId)
galleryRepository.requireWithLockById(galleryId)

val concept = conceptRepository.findByIdAndGalleryId(conceptId, galleryId)
    ?: throw CategoryException(CategoryErrorCode.CONCEPT_NOT_FOUND)

val detail = detailRepository.save(
    DetailFolder(concept.requiredId, request.name.trim(), sortOrder, CategorySource.USER),
)

return detailResponse(detail, emptyList())
```

## 검증 로직의 추출

- 특정 개념을 검증하는 로직이 커지거나, 반복되거나, 조건이 얽혀 복잡해지면 **개념이 드러나는
  이름의 private 메서드로 뽑는다** (`validateMinScore`, `requireConcept`, `validateEmbeddingComplete`).
  호출부에는 검증의 이름만 남고, 세부 조건은 메서드 안으로 들어간다.
- private 헬퍼는 부르는 메서드 바로 아래에 둔다 (service.md의 배치 규칙).
- 여러 서비스가 같은 검증을 반복하면 private 추출을 넘어 support로 옮길 신호다
  (`RetouchPhotoLoader`, support.md 참조).

## 네이밍

- 패키지는 도메인 단위로 나눈다 (`gallery`, `photo`, `category`, `collab`).
  전부 소문자 한 단어 — 도메인 이름이 두 단어가 되면 이름을 다시 생각하라.
- 클래스는 PascalCase (`AiCategoryFolderService`, `GalleryAccessPolicy`).
- 메서드는 camelCase + 역할이 드러나는 동사 접두사:
  - 조회: `find...`(nullable 반환) / `get...`(유스케이스) / `load...`(검증을 겸한 조회)
  - 강제: `require...`(아니면 예외) / `validate...`(검증만) / `lock...`(잠그고 조회)
- API 경로는 kebab-case 복수형 (`/api/v1/galleries`, `/concept-folders`, `/collab-sessions`).

## 주석

- 코드가 이미 말하는 것을 반복하는 주석은 달지 마라 — 무엇을 하는지는 이름과 구조로 드러낸다.
- **코드가 보여줄 수 없는 것은 KDoc으로 남긴다**: 정책의 이유, 버린 대안, 어기면 무엇이 깨지는지
  (`RetouchPhoto.galleryId`의 역정규화 근거, `AiConceptAssignment`의 읽기 전용 근거).
  이 저장소의 주석은 한국어로 쓴다.
- 리뷰어에게 말하는 주석("이 변경이 맞는 이유", "다음 줄이 하는 일")은 금지 — 머지되는 순간
  소음이 된다.
- 배경·정책이 문서 분량이면 주석이 아니라 CLAUDE.md 또는 `.claude/rules/`의 해당 파일에 남긴다.
