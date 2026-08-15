---
name: write-test
description: |
  서비스·서포트 클래스의 테스트 코드를 작성한다.
  Trigger: "테스트 작성해줘", "테스트 코드 만들어줘", "이 클래스 테스트해줘", "XXXService 테스트", "테스트 추가해줘"
  Do NOT use for: 테스트 실행/결과 확인만 필요한 경우(직접 gradle), 기존 테스트 수정만 필요한 경우(직접 Edit), 코드 리뷰(→ code-review)
  Boundary: 테스트 대상 코드의 버그 수정은 이 스킬 범위 밖이다. 테스트 작성 중 버그를 발견하면 사용자에게 보고만 하라.
allowed-tools: Read, Grep, Glob, Edit, Write, Bash
model: opus
effort: xhigh
---

# 테스트 코드 작성

대상: $ARGUMENTS

**인자 형식**: `<클래스명>` (테스트 대상 클래스명, 필수)
- 예: `PhotoSelectionService`, `CollabSessionAccess`, `GalleryInviteService`

모든 테스트는 통합 테스트(`@IntegrationTest`)로 작성한다. 이 저장소의 기본 테스트가 곧
통합 테스트이므로 테스트 클래스명은 `{대상}Test`다 — "Integration" 접미사를 붙이지 않는다.
(스프링 없이 도는 순수 단위 테스트를 따로 만들 때만 `{대상}UnitTest`.)

## Phase 1: 대상 분석

1. $ARGUMENTS의 클래스명으로 대상 클래스 파일을 Grep/Glob으로 찾아 Read로 읽어라.
2. **대상이 `service/` 또는 `support/` 클래스인지 확인하라.** 컨트롤러·repository·domain
   클래스를 지목받았다면 이 저장소의 계층 정책(`.claude/rules/test.md` — 테스트는 서비스·서포트
   빈을 직접 호출한다)을 안내하고, 그 규칙을 지나는 서비스가 무엇인지 제안한 뒤 사용자 확인을
   받아라. 예외는 계층 규약 회귀 가드(어댑터 테스트 등)뿐이다.
3. 클래스의 public 메서드 목록과 각 메서드의 분기(if/when/예외 throw)를 파악하라.
   `@Transactional` 유무·잠금(`findWithLockBy...`)도 시나리오 소재다.
4. 클래스가 의존하는 협력자(생성자 파라미터)를 목록화하고, 던지는 `{Domain}ErrorCode`
   상수를 exception enum 파일에서 확인하라. **존재하지 않는 메서드·상수로 테스트를 지어내지
   마라 — 전부 소스에서 확인한 것만 쓴다.**

> 다음 Phase 조건: 대상 클래스의 메서드·분기·의존성·에러코드 목록이 파악되었을 때

> Skip 조건: 없음 (필수 Phase)

## Phase 2: Fixture 확인 및 생성

1. `src/test/kotlin/com/soma/wes/{domain}/fixture/` 디렉토리들을 Glob으로 확인하라.
   현재 기본 제공: `UserFixture.사용자()`, `StudioFixture.작가()`,
   `GalleryFixture.멤버와_열린_갤러리()·멤버()·열기()·마감_지남()` (반환형 `OpenGallery`),
   `PhotoFixture.업로드된_사진()·대기중_사진()`, `FolderFixture.확정된_폴더()`.
2. 필요한 조합이 이미 있으면 재사용하라. 없으면
   [template/test-code-template.md](template/test-code-template.md)의 Fixture 섹션을 참조해
   그 도메인의 픽스처 클래스에 **메서드를 추가**하거나 새 픽스처 클래스를 만들어라.
   - `@TestComponent`가 아니라 `@Component`다 (스캔 등록 — 이유는 rules/test.md).
   - FixtureBuilder(플루언트 빌더) 클래스 금지 — 선택 인자는 named parameter + 기본값으로.
   - `ReflectionTestUtils`로 id를 심지 마라 — 저장하면 진짜 id가 나온다.
   - 유니크 값은 `support/TestSequence.next()`.
3. 검증 대상인 서비스 호출은 픽스처로 대체하지 마라 — 픽스처는 "배경"만 만든다.

> 다음 Phase 조건: 테스트에 필요한 모든 Fixture가 준비되었을 때

> Skip 조건: 대상 메서드가 저장된 엔티티를 쓰지 않거나, 필요한 Fixture가 전부 이미 존재할 때

## Phase 3: 테스트 코드 작성

1. `.claude/rules/test.md`를 읽어 테스트 컨벤션 전체를 확인하라.
2. [template/test-code-template.md](template/test-code-template.md)의 통합 테스트 템플릿을
   확인하고, 실제 기준 예시 `src/test/kotlin/com/soma/wes/selection/service/PhotoSelectionServiceTest.kt`도
   읽어라 (인가 정책 클래스라면 `gallery/support/GalleryAccessPolicyTest.kt`가 기준).
3. 컨벤션과 템플릿에 따라 `{대상 패키지 미러}/{대상}Test.kt`를 작성하라. 기능이 둘 이상이면
   `@Nested @DisplayName("~할 때") inner class`로 묶고, 거의 모든 테스트가 같은 첫 줄로
   시작할 때만 `@BeforeEach fun setUpBaseData()`로 끌어올린다.
4. Phase 1에서 파악한 각 메서드에 대해 다음 케이스를 작성하라:
   - 정상 동작 (성공 케이스 — 반환 DTO 필드 단언, 관련 단언 3개 이상이면 `assertSoftly`)
   - 예외 발생 (실패 케이스: 존재하지 않음, 권한 없음, 중복, 마감 등)
     - **`{Domain}Exception`을 던지는 케이스는 반드시 `errorCode`까지 검증**한다:
       ```kotlin
       assertThatThrownBy { target.method(...) }
           .isInstanceOf(SelectionException::class.java)
           .extracting("errorCode")
           .isEqualTo(SelectionErrorCode.PHOTO_ALREADY_SELECTED)
       ```
       타입만 검증하면 다른 errorCode로 회귀해도 통과하므로 회귀 감지가 불가능하다.
     - 인가 실패는 대개 `GalleryException` + `GalleryErrorCode`다 — 실제 던지는 쪽을 확인하라.
   - 엣지 케이스 (경계값·빈 목록·전부-아니면-거절의 "부분 성공 없음" DB 확인 등 — 해당할 때만)
   - Long 반환값(`count()` 류) 비교는 `isEqualTo(1L)`처럼 L 접미사 (박싱 불일치 함정).

> 다음 Phase 조건: 테스트 파일 작성이 완료되었을 때

> Skip 조건: 없음 (필수 Phase)

## Phase 4: 검증

1. `./gradlew compileTestKotlin`으로 컴파일을 확인하고, 실패하면 import 누락·시그니처
   불일치를 수정하라.
2. 누락된 테스트 케이스가 없는지 Phase 1의 분기 목록과 대조하라.
3. 작성 중 대상 코드의 버그를 발견했다면 고치지 말고 이 시점에 사용자에게 보고하라.

> 다음 Phase 조건: 컴파일이 통과하고 분기 대조가 끝났을 때

> Skip 조건: 없음 (필수 Phase)

## Phase 5: 테스트 실행

1. 작성한 테스트를 실행하라: `./gradlew test --tests "{패키지}.{테스트클래스명}"`
   (Testcontainers가 뜨므로 Docker가 필요하다 — 죽어 있으면 `open -a Docker` 후 대기).
2. 실패한 테스트가 있으면 에러를 분석하고 **테스트 쪽을** 수정하라. 실패 원인이 대상 코드의
   버그로 판명되면 수정하지 말고 사용자에게 보고하라 (Boundary).
3. 모든 테스트가 통과할 때까지 수정 → 재실행을 반복하라.
4. 최종 보고: 작성된 파일 경로, 테스트 메서드 수, 커버한 분기, (있다면) 발견한 버그.

> Skip 조건: 없음 (필수 Phase)
