---
paths:
  - "src/main/kotlin/**/*.kt"
---

# Domain 컨벤션

`{domain}/domain`은 엔티티·값객체와 그들이 스스로 지키는 규칙의 자리다.

## 엔티티 형태

- 일반 `class`(data class 금지) + `BaseEntity` 상속. `@Id @GeneratedValue`는 본문에 두고,
  non-null id가 필요한 자리는 `requiredId` 접근자를 쓴다.
- `@Column`은 `name`·`nullable`을 명시하고, 생성 후 바뀌지 않는 값은 `updatable = false`까지 적는다
  (`PhotoFolderItem.groupId`). 바뀌는 필드만 `var`, 나머지는 `val`.
- **스키마는 Flyway가 소유한다** (`ddl-auto=validate`). 엔티티·컬럼을 추가하면 반드시 마이그레이션을
  함께 쓴다. 버전 번호는 main 병합 시점 기준 최신 +1 — rebase 후 충돌 여부를 확인하라
  (V17 trash / V18 folder 충돌 전례).

## 생성과 검증: `of` 팩토리

- 외부 입력으로 만드는 엔티티·값객체는 companion의 `of` 팩토리로 만들고, **검증은 `of`에만 둔다.
  생성자/init 블록에서 검증하지 마라** — JPA 하이드레이션도 생성자를 지나므로, 규칙이 나중에
  엄격해지면 어제 저장한 행이 오늘 목록 조회를 깨뜨린다 (`FolderName.of`, `PhotoRating` 전례).
- 도메인 규칙 위반은 **도메인 예외를 직접 던진다**: `throw FolderException(FolderErrorCode.INVALID_FOLDER_NAME)`.
  `require`/`IllegalArgumentException`을 던지고 서비스에서 try-catch로 번역하는 방식은 쓰지 않는다.

## 값객체

- 의미 있는 문자열·묶음은 `@Embeddable` data class로 만든다 (`FolderName`, `PhotoMetadata`).
  `@Column` 매핑은 값객체 안에 두고, 엔티티는 `@Embedded`로 품는다. 검증은 역시 companion `of`.
- 여러 엔티티가 같은 규칙을 쓰면 값객체가 그 규칙의 단일 소유자가 된다
  (`PhotoFolderGroup`·`PhotoFolder`가 `FolderName` 하나를 공유).

## 불변식은 DB에도

- DB가 지킬 수 있는 불변식은 DB에도 새긴다: UK(`photo_folder_items(group_id, photo_id)`,
  `photo_ratings(photo_id)`), CHECK(`score BETWEEN 1 AND 5`), FK cascade. 애플리케이션 검증은
  좋은 에러 메시지를 위한 것이고, 최종 안전망은 제약이다.
- UK를 위해 필요한 역정규화는 허용하되 근거를 KDoc으로 남긴다
  (`PhotoFolderItem.groupId`: 부모 스코프 유니크를 DB가 지키려면 부모 id가 행에 있어야 한다).


## 상수·비교자

- **companion object는 클래스의 필드·함수보다 아래, 클래스 맨 끝에 둔다.** 읽는 순서는
  인스턴스의 형태(필드) → 행위(함수) → 정적 멤버다.
- 도메인 상수는 companion에 (`FolderName.MAX_LENGTH`, `PhotoAnalysis.EMBEDDING_DIMENSION` — 후자는
  마이그레이션·인프라 repo와 세 곳이 일치해야 한다).
- 여러 도메인이 쓰는 정렬 규칙은 그 값을 소유한 도메인의 companion에 한 번만 정의한다
  (`Photo.DISPLAY_ORDER`). 각자 복제하지 마라.
