package com.soma.wes.photo.controller

import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

@IntegrationTest
class PhotoRatingContractTest @Autowired constructor(
    private val mvc: MockMvc,
    private val tokens: AuthTokenProvider,
    private val galleries: GalleryFixture,
    private val photos: PhotoFixture,
) {
    @Test
    fun `기존 별점 쓰기 경로와 새 목록 경로에서 작업자와 수정 시각을 직렬화한다`() {
        // given
        val gallery = galleries.멤버와_열린_갤러리()
        val photoId = photos.업로드된_사진(gallery.galleryId, count = 1).single()
        val token = tokens.generateAccessToken(gallery.member).value
        val base = "/api/v1/galleries/${gallery.galleryId}"

        // when & then
        mvc.put("$base/photos/$photoId/rating") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"score":5}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.photoId") { value(photoId) }
            jsonPath("$.ratedBy") { value(gallery.member.requiredId) }
            jsonPath("$.updatedAt") { isString() }
        }
        mvc.get("$base/photo-ratings") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$[0].photoId") { value(photoId) }
            jsonPath("$[0].score") { value(5) }
            jsonPath("$[0].ratedBy") { value(gallery.member.requiredId) }
            jsonPath("$[0].updatedAt") { isString() }
        }
    }

    @Test
    fun `동기화 변경 번호는 인증된 참여자에게 숫자로 직렬화된다`() {
        // given
        val gallery = galleries.멤버와_열린_갤러리()
        val token = tokens.generateAccessToken(gallery.member).value

        // when & then
        mvc.get("/api/v1/galleries/${gallery.galleryId}/selection-sync") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.selectionRevision") { isNumber() }
            jsonPath("$.ratingRevision") { isNumber() }
        }
        mvc.get("/api/v1/galleries/${gallery.galleryId}/selection-sync")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `별점 전체 조회는 인증을 요구한다`() {
        // when & then
        mvc.get("/api/v1/galleries/1/photo-ratings").andExpect { status { isUnauthorized() } }
    }
}
