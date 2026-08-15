# 테스트 코드 템플릿 (Kotlin)

## 통합 테스트

위치는 대상 클래스의 패키지를 미러링한다: `src/test/kotlin/com/soma/wes/{domain}/service/{Target}Test.kt`
(서포트 클래스면 `{domain}/support/{Target}Test.kt`).

```kotlin
package com.soma.wes.{domain}.service

import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * {대상}을 서비스 경계에서 확인한다.
 *
 * {이 테스트가 지키는 핵심 규칙 한두 문장 — 무엇이 깨지면 안 되는가.}
 */
@IntegrationTest
class {Target}Test @Autowired constructor(
    private val target: {TargetClass},          // 검증 대상 서비스/서포트 빈
    private val galleryFixture: GalleryFixture, // 필요한 픽스처만
    private val photoFixture: PhotoFixture,
    private val repository: {Repository},       // "부분 성공 없음" 류 DB 확인용 (필요할 때만)
) {

    // 거의 모든 테스트가 같은 첫 줄로 시작할 때만 끌어올린다. 인자가 테스트마다 다르면 금지.
    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("{기능}할 때")
    inner class Context {

        @Test
        fun `성공한다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            // when
            val result = target.method(fixture.galleryId, fixture.member.id!!, ...)

            // then — 관련 단언 3개 이상이면 assertSoftly
            assertSoftly { softly ->
                softly.assertThat(result.field).isEqualTo(...)
                softly.assertThat(result.count).isEqualTo(2)
                softly.assertThat(result.items).hasSize(2)
            }
        }

        @Test
        fun `존재하지 않으면 예외를 던진다`() {
            // when & then
            // 예외 타입과 errorCode를 함께 검증한다 — errorCode 누락 시 회귀 감지 불가.
            assertThatThrownBy { target.method(fixture.galleryId, fixture.member.id!!, ...) }
                .isInstanceOf({Domain}Exception::class.java)
                .extracting("errorCode")
                .isEqualTo({Domain}ErrorCode.{ERROR_CODE})

            // 전부-아니면-거절이면 DB에 부산물이 없음을 함께 확인한다. Long은 L 접미사.
            assertThat(repository.count()).isEqualTo(0L)
        }
    }
}
```

주의:
- HTTP 상태·MockMvc·jsonPath는 쓰지 않는다 — 서비스 반환 DTO와 예외로 표현한다.
- 컨트롤러 bean validation(`@Valid`)은 서비스 직접 호출에서 돌지 않는다 — 도메인/서비스의
  2차 방어선이 실제로 던지는 예외로 검증한다.
- errorCode enum은 `SelectionErrorCode.PHOTO_ALREADY_SELECTED`처럼 한정 참조로 쓴다
  (이 저장소 관례 — static import 하지 않는다).
- 빈 교체 페이크가 필요하면 클래스에 `@TestConfiguration` + `@Bean @Primary`를 덧붙인다
  (`TrashServiceTest` 참조). 커스텀 프로퍼티까지 필요하면 rules/test.md의 예외 규정을 따른다.

## Fixture (도메인별 @Component)

위치: `src/test/kotlin/com/soma/wes/{domain}/fixture/{Domain}Fixture.kt`
리포지토리를 주입받아 **저장까지 마친 진짜 행**을 돌려준다. 메서드는 만들어지는 것의
이름을 한국어로 쓴다.

```kotlin
package com.soma.wes.{domain}.fixture

import com.soma.wes.support.TestSequence
import org.springframework.stereotype.Component

@Component
class {Domain}Fixture(
    private val repository: {Entity}Repository,
    private val userFixture: UserFixture,   // 다른 도메인 픽스처를 주입받아 조합한다
) {

    /** {이 배경이 어떤 상태인지 한 줄.} */
    fun 기본_{엔티티명}(owner: User, name: String = "기본값-${TestSequence.next()}"): {Entity} =
        repository.save({Entity}.create(owner.id!!, name, ...))
}
```

주의:
- `@TestComponent` 금지(스캔에서 제외되어 `@Import` 등록이 필요해지고, 그러면 컨텍스트
  캐시가 갈라진다) — 테스트 소스의 `@Component`로 충분하다.
- `ReflectionTestUtils.setField(entity, "id", ...)` 금지 — 저장하면 진짜 id가 나오고,
  가짜 id는 FK에서 조용히 어긋난다.
- FixtureBuilder(플루언트 빌더) 클래스 금지 — 선택 인자는 named parameter + 기본값.
- 유니크 제약을 피할 값은 파일별 `AtomicLong`이 아니라 `TestSequence.next()`.
- 여러 값을 함께 돌려줄 때는 같은 패키지의 data class로 묶는다 (`gallery/fixture/OpenGallery`).
