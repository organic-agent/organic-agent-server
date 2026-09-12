package com.soma.wes.retouch.service

import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.retouch.domain.RetouchRefineStatus
import com.soma.wes.retouch.dto.request.RefineRetouchRequest
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.support.FakeStructuredLlmClient
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class RetouchRefinementServiceTest @Autowired constructor(
    private val service: RetouchRefinementService,
    private val galleries: GalleryFixture,
    private val llm: FakeStructuredLlmClient,
) {
    @BeforeEach
    fun reset() = llm.reset()

    @Nested
    @DisplayName("요청문을 정제할 때")
    inner class Refine {

        @Test
        fun `원문을 보존하고 선택 가능한 정제안을 반환한다`() {
            // given
            val fixture = galleries.멤버와_열린_갤러리()
            llm.respondWith("""{"refinedText":"볼의 잡티를 자연스럽게 지워 주세요."}""")

            // when
            val result = service.refine(fixture.galleryId, fixture.member.requiredId, RefineRetouchRequest("볼 잡티 지워줘"))

            // then
            assertSoftly { softly ->
                softly.assertThat(result.originalText).isEqualTo("볼 잡티 지워줘")
                softly.assertThat(result.refinedText).isEqualTo("볼의 잡티를 자연스럽게 지워 주세요.")
                softly.assertThat(result.available).isTrue()
                softly.assertThat(result.status).isEqualTo(RetouchRefineStatus.READY)
                softly.assertThat(llm.calls).isEqualTo(1)
            }
        }

        @Test
        fun `비활성 모델은 정제했다고 가장하지 않는다`() {
            // given
            val fixture = galleries.멤버와_열린_갤러리()
            llm.isEnabled = false

            // when
            val result = service.refine(fixture.galleryId, fixture.member.requiredId, RefineRetouchRequest("밝게 해주세요"))

            // then
            assertSoftly { softly ->
                softly.assertThat(result.available).isFalse()
                softly.assertThat(result.refinedText).isNull()
                softly.assertThat(result.status).isNull()
                softly.assertThat(llm.calls).isZero()
            }
        }
    }

    @Nested
    @DisplayName("호출 전 게이트를 지날 때")
    inner class Gate {

        @Test
        fun `글자가 없는 입력은 모델을 부르지 않고 요청이 아니라고 답한다`() {
            // given
            val fixture = galleries.멤버와_열린_갤러리()

            // when
            val result = service.refine(fixture.galleryId, fixture.member.requiredId, RefineRetouchRequest("123 🙂"))

            // then
            assertSoftly { softly ->
                softly.assertThat(result.status).isEqualTo(RetouchRefineStatus.NOT_A_REQUEST)
                softly.assertThat(result.originalText).isEqualTo("123 🙂")
                softly.assertThat(result.refinedText).isNull()
                softly.assertThat(result.available).isTrue()
                softly.assertThat(llm.calls).isZero()
            }
        }

        @Test
        fun `공백뿐인 요청은 게이트 이전에 막는다`() {
            // given
            val fixture = galleries.멤버와_열린_갤러리()

            // when & then
            assertThatThrownBy {
                service.refine(fixture.galleryId, fixture.member.requiredId, RefineRetouchRequest("   "))
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.INVALID_POINT)
            assertThat(llm.calls).isZero()
        }
    }
}
