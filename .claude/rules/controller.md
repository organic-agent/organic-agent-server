---
paths:
  - "src/main/kotlin/**/*.kt"
---

# Controller 컨벤션

컨트롤러는 HTTP를 서비스 호출로 바꾸는 얇은 층이다. 로직·매핑·검증 규칙을 갖지 않는다.

## 책임

- 요청 DTO를 서비스에 그대로 넘기고, 서비스가 돌려준 응답 DTO를 `ResponseEntity`로 감싸기만 한다.
- **엔티티는 컨트롤러에 도달하면 안 된다.** `open-in-view`가 `false`라 트랜잭션을 벗어난 엔티티는
  detached 상태다 — 누군가 `@ManyToOne`을 추가하는 날, 엔티티를 매핑하던 컨트롤러가 원인과 무관한
  자리에서 `LazyInitializationException`을 던진다.
- 인가는 컨트롤러가 하지 않는다. 경로의 id들과 `loginUser.id`를 서비스에 넘기면,
  서비스 첫 줄의 정책 클래스(`GalleryAccessPolicy` 등)가 확인한다.

## 형태

- `@RestController` + `@RequestMapping("/api/v1/...")`. 중첩 자원은 부모 id를 경로에 싣는다
  (`/galleries/{galleryId}/folder-groups/{groupId}/folders`).
- **반환은 항상 block body의 `ResponseEntity<T>`.** expression body 금지, `@ResponseStatus` 금지 —
  상태 코드는 정확히 한 곳에만 있어야 한다.
  - 200: `return ResponseEntity.ok(result)`
  - 그 외: `val status = HttpStatus.CREATED` 후 `return ResponseEntity.status(status).body(result)`
  - 본문 없는 응답: `ResponseEntity.status(status).build()` (`ResponseEntity<Unit>`)
- 파라미터 순서: `@AuthenticationPrincipal loginUser: LoginUser` → `@PathVariable`들(경로 순서대로) →
  `@Valid @RequestBody request`.
- 요청 본문에는 `@Valid`를 반드시 붙인다. 단, bean validation이 규칙의 유일한 방어가 되게 하지 마라 —
  실제 불변식은 도메인/서비스가 지킨다 (dto.md 참조).

## OpenAPI 문서

- 컨트롤러는 같은 도메인의 `controller/docs/{Name}ControllerDocs` 인터페이스를 구현한다.
- `@Tag`·`@Operation`·`@ApiResponses`는 **docs 인터페이스에만** 둔다. 컨트롤러에는 매핑 애노테이션
  (`@PostMapping` 등)과 파라미터 바인딩 애노테이션만 남는다.
- 두 파일의 시그니처는 항상 함께 바뀐다. `@Operation.description`에는 필드 나열이 아니라 **정책**을
  적는다 — 누가 쓸 수 있고, 왜 409가 나는지 (`CollabSessionControllerDocs.open` 참조).

## 공개 경로

- 인증 없는 경로는 `PublicPaths`에 명시된 것뿐이다(현재 `/api/v1/collab/**`).
  새 공개 경로는 실수로 열리지 않는다 — 열어야 한다면 그 결정을 리뷰에서 드러내라.
