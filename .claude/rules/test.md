---
description: 테스트 작성 규칙 — 통합 테스트 인프라, 픽스처, 단언
paths:
  - "src/test/**/*.kt"
---

# 테스트 컨벤션

## 무엇을 테스트하는가 — 계층과 이름

- **테스트는 서비스·서포트 빈을 직접 호출한다** (`photoSelectionService.select(...)`).
  MockMvc로 HTTP를 두드리는 컨트롤러 테스트, repository·domain 계층별 테스트는 따로
  만들지 않는다 — 그 계층의 규칙은 서비스 경계 테스트가 지나며 함께 검증된다.
  예외는 특정 계층이 소유한 규약을 지키는 회귀 가드(`ErrorCodeFormatTest`,
  `JwtAuthFilterRegistrationTest`, `S3PhotoStorageTest` 같은 어댑터 테스트)뿐이다.
- 위치와 이름은 대상 클래스를 따른다: `{domain}/service/{Service}Test`,
  `{domain}/support/{Support}Test`. **"Integration" 접미사를 붙이지 마라** — 이 저장소의
  기본 테스트가 곧 통합 테스트라 수식어가 정보가 아니다. 나중에 스프링 없이 도는 순수
  단위 테스트가 필요해지면 그쪽에 `{대상}UnitTest`를 붙여 구별한다.
- HTTP 상태 검증은 예외 검증으로 표현된다: 4xx 시나리오는
  `assertThatThrownBy { ... }.extracting("errorCode")`로, 응답 본문 검증은 서비스가 돌려준
  DTO 필드 단언으로. 컨트롤러 bean validation(`@Valid`)은 서비스 직접 호출에서 돌지 않으니,
  그 시나리오는 도메인/서비스의 2차 방어선이 던지는 실제 예외로 검증한다.
- 기준 예시: `selection/service/PhotoSelectionServiceTest`.

## 통합 테스트는 `@IntegrationTest` 하나로 시작한다

- `src/test/.../support/IntegrationTest.kt`의 합성 애노테이션이
  `@SpringBootTest` + MockMvc + pgvector Testcontainers + 테스트 간 TRUNCATE 정리를 조립한다.
  개별 테스트에 그 조각들을 다시 나열하지 마라.
- **모든 통합 테스트가 같은 조합을 써야 Spring 컨텍스트 하나를 공유한다** (전체 스위트가
  30초대인 이유). 개별 테스트에 `@TestPropertySource`·목 빈을 붙이면 그 파일만 새 컨텍스트를
  띄운다. 공용 페이크가 필요하면 `@IntegrationTest`의 `@Import` 목록에 추가해 전원이 공유하게
  하라.
- 전용 컨텍스트가 정말 필요한 테스트(빈 교체 `@Primary` 페이크)는 `@IntegrationTest` 위에
  `@Import`를 덧붙인다(`TrashTest`, `TrashEraserTest`). 커스텀 프로퍼티까지 필요하면 평문
  `@SpringBootTest(properties=...)` 조합에 `@ExtendWith(DatabaseClearExtension::class)`만 얹는다.

## 데이터 정리는 DatabaseCleaner가 소유한다

- `@BeforeEach`에서 `deleteAllInBatch()` 체인을 만들지 마라. `DatabaseClearExtension`이
  매 테스트 전에 `pg_tables` 기준으로 전 테이블을 TRUNCATE 한다 — 새 마이그레이션이
  테이블을 추가해도 고칠 곳이 없다 (`flyway_schema_history`만 제외).
- DB 밖의 상태(기록형 페이크의 호출 기록 등)는 Cleaner가 모른다. 그런 것만
  `@BeforeEach`로 직접 리셋한다 (`photoStorage.reset()`).
- 테스트는 데이터 전제 없이 스스로 준비한다 — 계정 포함 전부 지워지므로, 앞 테스트가
  남긴 행에 기대는 테스트는 애초에 성립하지 않는다.

## 픽스처

- 픽스처는 **도메인별** `{domain}/fixture/{Domain}Fixture` — 리포지토리를 주입받는
  `@Component` 클래스이고, 메서드는 만들어지는 것의 이름을 한국어로 쓴다:
  ```kotlin
  @Component
  class GalleryFixture(
      private val galleryRepository: GalleryRepository,
      ...
  ) {
      fun 멤버와_열린_갤러리(maxSelectablePhotoCount: Int? = null): OpenGallery { ... }
      fun 멤버(galleryId: Long): User { ... }
  }
  ```
  현재: `UserFixture.사용자()`, `StudioFixture.작가()`, `GalleryFixture.멤버와_열린_갤러리()·멤버()·열기()·마감_지남()`,
  `PhotoFixture.업로드된_사진()·대기중_사진()`, `FolderFixture.확정된_폴더()`.
  테스트 파일에 `signUpUser` 류를 복제하지 마라 — 필요한 조합이 없으면 그 도메인의 픽스처에 추가한다.
- 픽스처는 다른 도메인의 픽스처를 주입받아 조합한다 (`StudioFixture`가 `UserFixture`를,
  `GalleryFixture`가 `StudioFixture`를). 여러 값을 함께 돌려줄 때는 같은 패키지의 data class로
  묶는다 (`gallery/fixture/OpenGallery`).
- **FixtureBuilder(플루언트 빌더) 클래스를 만들지 마라.** Java의 `name(...).maxLp(...).create()`
  체인이 하던 일은 Kotlin의 named argument + 기본값이 언어 차원에서 대신한다 — 선택 인자가
  늘면 빌더가 아니라 픽스처 메서드에 기본값 있는 파라미터를 추가한다
  (`멤버와_열린_갤러리(maxSelectablePhotoCount = 3)`).
- **`@TestComponent`가 아니라 `@Component`다.** `@TestComponent`는 스캔에서 제외되어
  `@IntegrationTest`의 `@Import`에 올려야 하고, 그러면 컨텍스트 캐시가 갈라진다. 테스트 소스의
  `@Component`는 스캔만으로 모든 테스트 컨텍스트에 등록되고 프로덕션 클래스패스에는 없다.
- **통합 테스트에서 `ReflectionTestUtils`로 id를 심지 마라.** 저장하면 진짜 id가 나온다 —
  가짜 id는 FK가 있는 테이블에서 조용히 어긋난다. id 심기는 저장 없이 도는 단위 테스트 한정.
- 유니크 값이 필요하면 파일마다 `AtomicLong`을 만들지 말고 `support/TestSequence.next()`를 쓴다.
- 검증 대상인 서비스 호출은 픽스처로 대체하지 않는다 — 업로드 URL 발급이 검증 대상인
  테스트는 `photoFixture`가 아니라 `PhotoService`를 직접 불러 만든다. 픽스처는 "배경"만 만든다.

## 구조와 단언

- 테스트가 여럿인 클래스는 기능별 `@Nested @DisplayName("{기능}할 때")`로 묶는다. Kotlin에서
  `@Nested`는 **`inner class`**여야 한다. 메서드명은 한글 백틱(`` `성공한다` ``).
  주제가 하나뿐인 작은 파일(3개 이하)은 `@Nested` 없이 두어도 된다.
- 모든(또는 거의 모든) 테스트가 같은 첫 줄로 같은 배경을 만들면 그 줄만
  `private lateinit var` 필드 + `@BeforeEach fun setUpBaseData()`로 끌어올린다.
  인자가 테스트마다 다르면(selection의 `maxSelectablePhotoCount`처럼) 끌어올리지 않는다 —
  변형은 각 테스트의 given에 있어야 보인다.
- 본문에는 `// given` / `// when` / `// then` 주석으로 단계를 표시한다. 준비가 없으면
  given 생략, mockMvc 호출 하나가 실행과 검증을 겸하면 `// when & then` 하나로 합친다.
- 단언은 **AssertJ**로 통일한다: `assertThat(actual).isEqualTo(expected)`, 관련 단언이
  3개 이상 이어지면 `assertSoftly { softly -> ... }`. kotlin-test 단언을 쓰지 마라 —
  한 저장소에 단언 스타일 둘을 두지 않는다. MockMvc의 `andExpect { jsonPath... }` 블록은
  그 자체가 then이라 그대로 둔다.
  - 함정: `assertThat(repository.count()).isEqualTo(1)`은 Long↔Integer 박싱 불일치로
    실패한다. Long 비교는 `isEqualTo(1L)`처럼 L 접미사를 붙인다.
- 예외는 타입과 errorCode를 함께 검증한다 — errorCode를 빼먹으면 코드가 바뀌는 회귀를
  잡지 못한다:
  ```kotlin
  assertThatThrownBy { target.method(...) }
      .isInstanceOf(CollabException::class.java)
      .extracting("errorCode")
      .isEqualTo(CollabErrorCode.FOLDER_NOT_FOUND)
  ```

## 테스트 인프라의 자리

- 테스트 루트 `support/`: `IntegrationTest`, `TestcontainersConfiguration`,
  `DatabaseCleaner`, `DatabaseClearExtension`, `TestSequence`.
  프로덕션 `support`가 "유스케이스가 기대는 협력자"이듯, 여기는 테스트가 기대는 인프라다.
  도메인에 속하는 것(픽스처)은 여기가 아니라 `{domain}/fixture`에 산다.
- Testcontainers 이미지는 `pgvector/pgvector:pg16` 고정 — stock `postgres`로 바꾸면 V2의
  `CREATE EXTENSION vector`가 깨진다 (rules/migration.md).
