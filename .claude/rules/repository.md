---
paths:
  - "src/main/kotlin/**/*.kt"
---

# Repository 컨벤션

`{domain}/repository`는 Spring Data JPA 인터페이스의 자리다. 조회 스코프와 잠금이라는
정책의 절반이 여기서 표현된다.

## 조회 메서드

- 파생 쿼리 메서드(`findByIdAndGroupId`)를 우선 쓴다. 이름으로 표현이 안 될 때만 `@Query`.
- **스코프는 쿼리 안에서 건다.** 경로에 부모가 있는 자원은 `findByIdAnd{Parent}Id`로 찾는다 —
  남의 갤러리/부모의 id를 자기 경로에 끼워 넣는 요청이 조회 단계에서 404가 된다. id로 찾은 뒤
  코드에서 소유자를 비교하는 방식은 쓰지 않는다 (`PhotoFolderGroupRepository.findByIdAndGalleryId`
  KDoc 참조).
- 정렬이 정책이면 메서드 이름에 새긴다 (`findAllByGroupIdOrderByIdAsc` — 만든 순서 유지).
- 반환은 nullable(`PhotoFolderGroup?`)로 두고 예외 번역은 호출자(서비스)가 한다 —
  `?: throw FolderException(GROUP_NOT_FOUND)`가 기본형이다.
- `requireById` 같은 get-or-throw 확장 함수는 처음부터 만들지 않는다. **여러 클래스에서 같은
  조회 + 같은 예외의 조합이 반복될 때** 비로소 repository 패키지에 뽑는다
  (`GalleryRepository.requireById`가 그렇게 생겼다). 한두 곳뿐이면 서비스의 `?: throw`로 충분하다.

## 잠금

- 비관적 잠금은 `@Lock(LockModeType.PESSIMISTIC_WRITE)`를 붙인 `findWithLockBy...` 변형으로
  제공한다. 어떤 쓰기가 왜 잠그는지는 KDoc 한 줄로 남긴다.

## 벌크 연산

- 여러 행을 갱신/삭제하는 경로는 엔티티 루프가 아니라
  `@Modifying(clearAutomatically = true, flushAutomatically = true)` + JPQL `@Query`로 처리한다
  (`PhotoFolderItemRepository.moveAll`, `deleteAllByGroupId`).
- 영향 행 수를 반환해 호출자가 0건을 판정할 수 있게 한다
  (`deleteByFolderIdAndPhotoId` 0건 → `PHOTO_NOT_IN_FOLDER`).
- 벌크 쿼리가 전제하는 선행 조건(예: "호출자가 부모를 잠그고 확인했다")은 KDoc에 적는다.

## 소프트 삭제와 native SQL

- JPA 경로는 `@SQLRestriction`이 `deleted_at` 필터를 자동으로 건다. **`TrashRepository` 밖에서
  native SQL을 쓰면 `deleted_at IS NULL`을 직접 걸어야 한다.**
  휴지통 행을 읽는 코드는 전부 `trash` 도메인의 `TrashRepository`(JdbcClient)로 모은다.

## 기타

- 조회 전용 projection은 `repository/projection`에 둔다.
- id 뭉치를 받는 파라미터는 `Collection<Long>`으로 받는다 — 호출자가 List든 Set이든 상관없다.
- 새 테이블을 만들면 `scripts/reset-test-data.sh`의 TRUNCATE 목록에 추가한다 — V9 이후 FK 때문에
  참조하는 테이블이 하나라도 빠지면 문 전체가 거절된다.
