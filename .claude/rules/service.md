---
paths:
  - "src/main/kotlin/**/*.kt"
---

# Service 컨벤션

`{domain}/service`는 컨트롤러가 부르는 유스케이스의 자리다. 유스케이스를 돕는 협력자는
`support`로 (support.md), 외부 시스템은 port/adapter로 (infrastructure.md) 분리한다.

## 시그니처

- 요청 DTO를 받고 응답 DTO를 돌려준다. 엔티티는 서비스 밖(컨트롤러)으로 내보내지 않는다.
  서비스 ↔ 서비스/서포트 사이의 엔티티 전달은 트랜잭션 안이므로 허용
  (`GalleryAccessPolicy.requirePhotographer`가 `Gallery`를 반환).
- 파라미터 순서: 경로 id들(부모 → 자식) → `userId` → `request`.

## 트랜잭션

- **`@Transactional`은 메서드에만, 클래스에 절대 붙이지 않는다.** 클래스 레벨은 나중에 추가되는
  메서드까지 조용히 등록해서, S3·Lambda·OAuth를 부르는 메서드가 커넥션을 문 채 네트워크를 기다리게
  된다. 쓰기는 `@Transactional`, 읽기는 `@Transactional(readOnly = true)`, DB를 안 만지는 메서드는
  무표기 (`EmbeddingService.run`이 일부러 비어 있는 이유).
- **같은 클래스 안에서 `@Transactional` 메서드를 호출하지 마라.** 프록시를 우회해 애노테이션이
  조용히 무시된다. 헬퍼는 private·무표기로 두어 호출자의 트랜잭션을 물려받게 하거나, 트랜잭션이
  필요한 부분을 별도 빈으로 뺀다 (`OAuthLoginService` → `OAuthLoginProcessor` 전례).

## 유스케이스의 뼈대

1. **첫 줄은 인가.** 정책 클래스의 `require*`를 부른다. 어느 문이 맞는지는 "보는 것인가,
   고르는 것인가"로 정한다 — 순수 조회는 `requireViewer`, 선택 행위는 `requireCouple` 또는
   `requirePhotographerOrCouple` (CLAUDE.md의 역할 정의 참조).
2. **자원은 스코프째 찾는다.** `findByIdAndGalleryId`처럼 부모 id를 함께 걸어 찾고, 없으면
   도메인 예외로 404를 던진다 (repository.md 참조). id만으로 찾은 뒤 소유자를 비교하지 않는다.
3. **스코프 불변식을 지키는 쓰기는 스코프의 행을 먼저 잠근다.** 부모 스코프 사진 중복은 부모 행
   (`lockGroup`), 선택 앨범 정원은 갤러리 행 (`findWithLockById`). 잠글 행은 "불변식의 단위가
   되는, 반드시 존재하는 행"이다 — 아직 없을 수 있는 행은 뮤텍스가 못 된다.
4. **전부 아니면 거절.** 배치 요청에 잘못된 항목이 섞이면 맞는 것만 처리하지 말고 전체를
   거절한다 (중복 409, 남의 갤러리 사진 400). 부분 성공은 성공처럼 보이는데 무엇이 빠졌는지
   아무도 말할 수 없다.
5. **검증이 저장보다 먼저다.** 거절될 요청이 부산물(빈 부모폴더, 이름 없는 세션)을 남기지 않게,
   저장 전에 전부 검증한다 (`CategoryService.movePhotos`, `CollabSessionService.open` 전례).

## 예외

- 실패는 항상 `{Domain}Exception({Domain}ErrorCode.X)`로 던진다. `require`·표준 예외를 던지고
  나중에 번역하는 방식은 쓰지 않는다. 새 ErrorCode는 `{DOMAIN}_{HTTP}_{N}` 형식이고
  `ErrorCodeFormatTest`에 enum을 등록한다.

## 배치와 규모

- id 목록을 받는 경로는 `maxBatchSize`류 상한을 확인한다.
- 목록 응답 조립은 배치로 (support.md). 루프 안의 단건 조회·단건 조립은 리뷰에서 막는다.

## 코드 배치

- private 헬퍼는 **부르는 메서드 바로 아래**에 둔다. 여러 곳이 부르면 가장 아래 호출자 밑에.
  파일 하단으로 쓸어 모으지 마라.
- public 메서드의 KDoc에는 정책과 그 이유를 적는다 — 시그니처가 이미 말하는 것을 반복하지 않는다.
