---
description: Kotlin 관용구 — 널 처리, 불변성, 컬렉션, scope function, 타입 설계. 계층 규칙(common.md 등)과 충돌하면 계층 규칙이 이긴다
paths:
  - "src/main/kotlin/**/*.kt"
---

# Kotlin 관용구

Java를 Kotlin 문법으로 옮긴 코드가 아니라 Kotlin으로 생각한 코드를 쓴다. 다만 이 파일은
**표현 방식**만 다룬다 — 예외·계층·트랜잭션 같은 정책은 `common.md`와 계층 파일이 소유하고,
관용구가 그 정책과 부딪히면 정책이 이긴다.

## 실패의 두 종류: 도메인 예외 vs 불변식 위반

`common.md`의 "`require`·`check` 금지"는 **외부 입력과 비즈니스 규칙의 실패**에 대한 규칙이다.
둘을 구분한다:

| 실패 | 누가 일으키나 | 쓰는 것 |
|---|---|---|
| 요청이 규칙을 어김 (없음, 권한, 중복, 상한) | 클라이언트 | `throw {Domain}Exception({Domain}ErrorCode.X)` |
| 코드가 전제를 어김 (저장 전 id, 도달 불가 분기, 비어선 안 되는 결과) | 개발자 버그 | `checkNotNull(x) { "이유" }`, `error("이유")` |

- 불변식 위반은 500이 맞다 — ErrorCode를 만들어 4xx로 포장하면 버그가 클라이언트 탓으로 보인다.
- 불변식 메시지에는 **무엇이 왜 불가능한지**를 적는다: `error("아직 저장되지 않은 RetouchRound 다")`.
- `requiredId` 형태는 하나로 맞춘다: `val requiredId: Long get() = checkNotNull(id) { "아직 저장되지 않은 {Entity} 다" }`.

## 널 처리

- **`!!` 금지.** 널일 수 없다고 믿는 근거를 코드에 남긴다:
  - 요청 필드가 앞 분기에서 검증됐다면 → 검증 결과를 non-null 지역 변수로 받아 이어 쓴다
    (`val photoId = request.photoId ?: throw ...`). 검증과 사용이 떨어져 `!!`로 다시 꺼내지 않는다.
  - Java API가 플랫폼 타입 `T?`를 돌려주지만 실제로는 널이 아니면 → `checkNotNull(x) { "이유" }`.
  - `TransactionTemplate.execute { }`의 결과처럼 반복되는 자리는 이유를 담은 헬퍼 하나로 모은다.
- Spring Data의 `findById(id).orElseThrow()` 대신 `findByIdOrNull(id) ?: throw ...`
  (`org.springframework.data.repository.findByIdOrNull`). `Optional`은 Kotlin 코드에 들이지 않는다.
- 널 분기는 `?:`로 끝낸다. `if (x == null) return/throw` 다음 줄에서 스마트 캐스트를 기대하는 것보다
  `val y = x ?: return` 한 줄이 낫다.
- `?.let { }`은 "값이 있으면 변환한다"에만 쓴다. 부수효과만 있는 분기(`x?.let { save(it) }`)는
  `if (x != null) save(x)`가 더 읽힌다. `?.let { } ?: run { }` 형태의 if-else 흉내는 금지.

## 불변성

- 필드·지역 변수는 `val`이 기본. `var`는 엔티티의 바뀌는 상태와 루프 누적처럼 불가피할 때만.
- 공개 타입은 읽기 전용 `List`·`Set`·`Map`. `MutableList`는 함수 안에서만 쓰고 밖으로 내보내지 않는다.
- 가변 컬렉션을 채워 돌려주는 패턴은 `buildList { }`·`buildMap { }`, 또는 변환 연산으로 대체한다:
  ```kotlin
  // X
  val result = mutableMapOf<Long, List<Photo>>()
  for (photo in photos) { result.getOrPut(photo.galleryId) { mutableListOf() }.add(photo) }
  // O
  val byGallery = photos.groupBy { it.galleryId }
  ```

## 컬렉션 연산

- for문으로 map·filter·집계를 손으로 짜지 않는다: `map`, `filter`, `associateBy`, `associateWith`,
  `groupBy`, `partition`, `sumOf`, `maxByOrNull`, `firstOrNull`, `chunked`, `windowed`, `zip`.
- 부수효과 반복은 **`for`가 기본**이다. `forEach`는 수신자가 nullable(`photos?.forEach`)이거나
  긴 체인의 끝(`...filter { }.forEach { }`)일 때만 쓴다 (Kotlin 공식 코딩 컨벤션).
  결과를 모으는 `forEach { list.add(..) }`는 `map`이고, 라벨 return(`return@forEach`)은 쓰지 않는다.
- 조회용 인덱스는 루프 밖에서 한 번 만든다 — 루프 안의 `list.find { }`·`list.filter { }`는 O(n²)다:
  `val photoById = photos.associateBy { it.requiredId }`.
- 체인이 4단을 넘거나 중간 결과에 의미가 있으면 **이름 있는 지역 변수로 끊는다**. 한 줄 체인이
  정답은 아니다.
- 큰 컬렉션을 여러 번 변환해 중간 리스트가 쌓이면 `asSequence()`를 고려하되, 수백 건 이하에선 쓰지 않는다.

## Scope function

| 함수 | 쓰는 자리 |
|---|---|
| `apply` | 객체 설정 후 그 객체 반환 (`TransactionTemplate(...).apply { propagationBehavior = ... }`) |
| `also` | 흐름을 끊지 않는 부수효과 (로그, 캐시 적재) |
| `let` | 널 가능 값의 변환, 스코프를 좁히는 지역 변환 |
| `run`·`with` | 쓰지 않는다 — 대부분 지역 변수 하나가 더 읽힌다 |

- **scope function을 중첩하지 않는다.** `it`이 두 겹이면 이미 읽을 수 없다. 중첩되면 지역 변수로 푼다.
- 람다 인자가 한 줄을 넘으면 `it` 대신 이름을 붙인다: `photos.map { photo -> ... }`.

## runCatching·Result

`runCatching`은 모든 `Throwable`을 잡는다 — 도메인 예외, `InterruptedException`, 트랜잭션을
롤백시켜야 할 예외까지. 그래서 쓰는 자리를 좁힌다:

- **쓰는 자리**: 외부 시스템 어댑터(S3·Lambda·Bedrock·OAuth·EC2 — `infrastructure/`)에서 실패를 호출자가
  고르게 할 때, 파싱 실패 → null (`runCatching { Role.valueOf(...) }.getOrNull()`), 정리 작업의 best-effort
  (`MockGalleryService`의 롤백 청소).
- **쓰지 않는 자리**: `@Transactional` 메서드 안의 도메인 로직. 예외를 삼키면 롤백이 일어나지 않고
  부분 저장이 커밋된다.
- 삼킬 때는 무엇을 왜 삼키는지 드러나게 쓰고, 로그 없이 삼키는 것은 금지.
- 도메인 전반의 반환형을 `Result<T>`로 바꾸지 않는다 — 실패는 도메인 예외가 말한다 (`common.md`).

## 함수

- 본문이 식 하나인 짧은 위임·변환은 expression body(`= ...`). 분기·단계가 있는 유스케이스는
  블록 body — `common.md`의 "단계를 빈 줄로 구분"이 보이지 않게 되기 때문이다.
- 인자 2개 이상의 생성자·DTO 호출은 named argument (`common.md`). 같은 타입 인자가 나란히 있는
  함수(`Long, Long, Long`) 호출도 named argument로 쓴다 — 순서 실수를 컴파일러가 못 잡는다.
- 기본값 인자로 오버로드를 대체한다. `@JvmOverloads`는 Java 호출자가 없으면 쓰지 않는다.
- **확장 함수**의 자리는 쓰는 범위가 정한다 (Kotlin 공식 컨벤션: "모든 사용처가 쓰면 클래스 옆에,
  한 사용처만 쓰면 그 옆에, 확장만 모은 파일은 만들지 않는다"):
  - 한 클래스 안에서만 쓰는 변환 → `private fun Foo.toBar()` (그 클래스 안, 부르는 메서드 아래)
  - 여러 서비스가 반복하는 repository 조회+예외 → 그 repository 파일의 최상위 확장
    (`GalleryRepository.requireById`, 생기는 조건은 `repository.md`).
  - 도메인 행위를 확장 함수로 빼지 않는다 — 엔티티의 메서드다. `StringExtensions.kt`·`*Utils.kt`류 파일을 만들지 않는다.
  - 가시성은 가장 좁게 (`private` → `internal` → public).

## 타입 설계

- **닫힌 상태·결과 집합은 `enum` 또는 `sealed interface`**, 분기는 `else` 없는 `when`으로 쓴다 —
  새 상태를 추가하면 처리하지 않은 분기가 컴파일 에러가 되게 한다. `else -> error(...)`는
  문자열·정수처럼 열린 입력을 받을 때만.
- 문자열 키로 분기하는 코드(`"balance" ->`, `material.map("balance")`)는 enum/sealed로 올릴 신호다.
- 의미가 다른 같은 원시 타입이 섞이면 `@JvmInline value class` (`AccessToken`, `Subject`).
  단, JPA 엔티티 필드·Spring Data 메서드 시그니처에는 쓰지 않는다 — 매핑이 깨진다.
- `data class`는 DTO·값객체에만. **엔티티는 `class`** (`domain.md`): `equals`/`hashCode`/`copy`가
  영속성 컨텍스트와 지연 로딩을 깨뜨린다.
- **엔티티끼리는 `==`로 비교하지 않고 `requiredId`로 비교한다** (`a.requiredId == b.requiredId`,
  `photos.map { it.requiredId }.toSet()`). 이 저장소의 엔티티는 `equals`/`hashCode`를 재정의하지 않아
  `==`가 객체 동일성이다 — 같은 영속성 컨텍스트 안에서만 맞고, 다른 트랜잭션에서 읽은 엔티티나
  지연 로딩 프록시(`Photo$HibernateProxy`)와 비교하면 같은 행인데도 `false`가 된다. 엔티티를 `Set`·`Map`
  키에 넣을 때도 같은 이유로 id를 키로 쓴다.
- `object`는 상태 없는 순수 함수 묶음·상수에만. 스프링 빈이 필요한 것은 `object`로 만들지 않는다.
- `lateinit`·필드 주입(`@Autowired`) 금지 — 생성자 주입만.

## 문자열·시간

- 문자열 조립은 템플릿(`"$a/$b"`), 여러 줄은 `trimIndent()`된 raw string. `+` 연결과 `String.format` 금지.
- 현재 시각은 주입받은 `Clock`으로 (`ZonedDateTime.now(clock)`) — `now()` 무인자 호출은 테스트를 막는다.
