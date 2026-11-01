---
paths:
  - "wes-*/src/main/kotlin/**/*.kt"
---

# Domain 컨벤션

`{domain}/domain`은 엔티티·값객체와 그들이 스스로 지키는 규칙의 자리다.

## 엔티티 형태

- 일반 `class`(data class 금지) + `BaseEntity` 상속. <!-- DM-01 DM-02 --> 예외: 추가만 하고 고치지 않는 이력 행(`AnalysisJobEvent`)은 상속하지 않을 수 있다 — KDoc에 사유. `@Id @GeneratedValue`는 본문에 두고,
  non-null id가 필요한 자리는 `requiredId` 접근자를 쓴다.
- `@Column`은 `name`을 명시하고, 생성 후 바뀌지 않는 값은 `updatable = false`까지 적는다 <!-- DM-03 -->
  (`RetouchPhoto.galleryId`). 바뀌는 필드만 `var`, 나머지는 `val`. <!-- DM-04 -->
- **엔티티의 `var`에는 `protected set`을 붙이고, 상태는 엔티티 메서드로만 바꾼다** (`gallery.open()`, `gallery.applyPass(pass)`,
  `gallery.archiveAfter(days, now)`, `invite.revoke(now)`). Kotlin의 `var`는 public setter를 만들어 서비스가
  `gallery.status = OPEN`처럼 직접 대입할 수 있게 되고, 그러면 상태 전이 검증(`open()`)을 건너뛰고 같은 계산(보관 기한)이
  서비스마다 복제된다. allOpen 플러그인 때문에 `private set`은 쓸 수 없어 `protected set`이 가장 좁다. <!-- DM-11 DM-12 -->
- **NULL 허용 여부는 Kotlin 타입의 `?`로 표현하고, DB 컬럼의 `NOT NULL`과 반드시 일치시킨다.** `@Column(nullable = …)`은
  적지 않는다 — 스키마는 Flyway가 만들고, `ddl-auto=validate`는 NULL 허용을 비교하지 않으며, Bean Validation이 있어
  Hibernate의 저장 전 null 검사도 꺼져 있어서 이 속성은 아무것도 막지 않는다. <!-- DM-s1 --> 실제로 막는 것은 Kotlin 타입(컴파일)과
  DB 제약(실행) 둘이고, 위험은 둘이 어긋날 때다: Kotlin `String` ↔ DB NULL 허용이면 NULL 행을 읽을 때 엉뚱한 곳에서 NPE,
  Kotlin `String?` ↔ DB `NOT NULL`이면 저장 순간 DB 오류. <!-- DM-10 -->
- **스키마는 Flyway가 소유한다** (`ddl-auto=validate`). 엔티티·컬럼을 추가하면 반드시 마이그레이션을
  함께 쓴다. 버전 번호는 main 병합 시점 기준 최신 +1 — rebase 후 충돌 여부를 확인하라
  (V17 trash / V18 folder 충돌 전례).

## 생성과 검증: `of` 팩토리

- 외부 입력으로 만드는 엔티티·값객체는 companion의 `of` 팩토리로 만들고, **검증은 `of`에만 둔다.
  생성자/init 블록에서 검증하지 마라** — JPA 하이드레이션도 생성자를 지나므로, 규칙이 나중에
  엄격해지면 어제 저장한 행이 오늘 목록 조회를 깨뜨린다 (`CollabSession.of`, `PhotoRating.of` 전례). <!-- DM-05 -->
- 도메인 규칙 위반은 **도메인 예외를 직접 던진다**: `throw FolderException(FolderErrorCode.EMPTY_PHOTO_IDS)`.
  `require`/`IllegalArgumentException`을 던지고 서비스에서 try-catch로 번역하는 방식은 쓰지 않는다.

## 값객체

- 엔티티의 필드로 들어가지만 자기 id가 없는(혼자 존재하지 않는) 묶음은 `@Embeddable` **일반 `class`**로 만든다 —
  필드 하나면 `@Embedded`(`Photo.metadata: PhotoMetadata`), 여러 개면 `@ElementCollection`
  (`DetailFolderMerge.movedPhotos: List<MergedPhotoSnapshot>`). `@Column` 매핑은 값객체 안에 둔다.
- data class가 아닌 이유: DTO는 `data class`, 엔티티와 그에 딸린 값객체는 `class`다. `copy()`가 `of`를 거치지 않는
  두 번째 생성자가 되기 때문이다. 외부 입력으로 만들 때만 companion `of`에서 검증한다.
- 여러 엔티티가 같은 규칙을 쓰면 값객체가 그 규칙의 단일 소유자가 된다.

## 불변식은 DB에도

- DB가 지킬 수 있는 불변식은 DB에도 새긴다: UK(`detail_folder_assignments(photo_id)`,
  `photo_ratings(photo_id)`), CHECK(`score BETWEEN 1 AND 5`), FK cascade. 애플리케이션 검증은
  좋은 에러 메시지를 위한 것이고, 최종 안전망은 제약이다. <!-- DM-07 -->
- UK를 위해 필요한 역정규화는 허용하되 근거를 KDoc으로 남긴다
  (`RetouchPhoto.galleryId`: 갤러리 스코프 조회가 회차를 거치지 않으려면 갤러리 id가 행에 있어야 한다).


## 상수·비교자

- **엔티티·값객체의 companion object는 클래스 맨 끝에 둔다.** 읽는 순서는 상태(필드) → 행위(메서드) →
  만드는 법(`of` 팩토리·도메인 상수)이다. 엔티티가 아닌 클래스는 반대로 필드 바로 아래다(`common.md` 상수 절). <!-- DM-08 -->
- 도메인 상수는 companion에 (`CollabSession.MAX_NAME_LENGTH`, `PhotoAnalysis.EMBEDDING_DIMENSION` — 후자는
  마이그레이션·인프라 repo와 세 곳이 일치해야 한다).
- 여러 도메인이 쓰는 정렬 규칙은 그 값을 소유한 도메인의 companion에 한 번만 정의한다
  (`Photo.DISPLAY_ORDER`). 각자 복제하지 마라. <!-- DM-09 -->
