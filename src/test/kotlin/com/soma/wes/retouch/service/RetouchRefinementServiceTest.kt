package com.soma.wes.retouch.service

import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.retouch.dto.request.RefineRetouchRequest
import com.soma.wes.support.FakeStructuredLlmClient
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
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
        assertThat(result.available).isFalse()
        assertThat(result.refinedText).isNull()
        assertThat(llm.calls).isZero()
    }
}
