package com.soma.wes.gallery.support

import com.soma.wes.photo.domain.Photo
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

/**
 * 복제 계획은 로직 없는 값이다. EMBEDDED만 고르는 규칙은 [MockGallerySeeder.loadTemplatePhotos]가,
 * 키 재발급(`galleries/{새id}/…`)·미리보기 복사 쌍·순서 재부여는
 * [com.soma.wes.gallery.service.MockGalleryService]와 [MockGallerySeeder.persistPhotos]가 소유하고
 * 그쪽 테스트가 지킨다. 여기서는 계획이 데이터로서 약속하는 것만 고정한다 — 원본 행과
 * 목적지 키의 쌍, 그리고 파생본 없는 원본을 null로 표현한다는 것. 저장이 없어 스프링 없이 돈다.
 */
class MockGalleryCopyPlanTest {

    @Test
    fun `원본 행과 새 갤러리 키 공간의 목적지를 쌍으로 담는다`() {
        // given
        val source = 템플릿_사진(previewKey = "previews/galleries/1/template-0.jpg")

        // when
        val plan = MockGalleryCopyPlan(
            source = source,
            storageKey = "galleries/2/copy-0.png",
            previewKey = "previews/galleries/2/copy-0.jpg",
        )

        // then
        assertSoftly { softly ->
            // 원본은 복사본이 아니라 같은 행이다 — persistPhotos가 이 행의 벡터·촬영 정보를 떠 간다.
            softly.assertThat(plan.source).isSameAs(source)
            softly.assertThat(plan.storageKey).isEqualTo("galleries/2/copy-0.png")
            softly.assertThat(plan.previewKey).isEqualTo("previews/galleries/2/copy-0.jpg")
        }
    }

    @Test
    fun `파생본 없는 원본은 previewKey 없는 계획으로 표현된다`() {
        // null은 "원본만 복사"라는 뜻이고, 복제 행도 previewKey 없이 남아
        // viewKey가 원본으로 폴백하는 일반 의미론을 그대로 따른다.
        // given
        val source = 템플릿_사진(previewKey = null)

        // when
        val plan = MockGalleryCopyPlan(
            source = source,
            storageKey = "galleries/2/copy-0.png",
            previewKey = null,
        )

        // then
        assertThat(plan.previewKey).isNull()
    }

    private fun 템플릿_사진(previewKey: String?): Photo =
        Photo(
            galleryId = 1L,
            storageKey = "galleries/1/template-0.png",
            originalFileName = "template-0.png",
            contentType = "image/png",
            displayOrder = 0,
        ).also { it.previewKey = previewKey }
}
