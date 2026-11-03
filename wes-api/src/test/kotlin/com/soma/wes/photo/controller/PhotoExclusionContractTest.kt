package com.soma.wes.photo.controller

import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

@IntegrationTest
class PhotoExclusionContractTest @Autowired constructor(
    private val mvc: MockMvc,
    private val tokens: AuthTokenProvider,
    private val galleries: GalleryFixture,
    private val photos: PhotoFixture,
) {
    @Test
    fun `정적 exclusions 경로에서 제외 메타데이터 페이지와 변경 번호를 직렬화한다`() {
        // given
        val gallery = galleries.멤버와_열린_갤러리()
        val id = photos.업로드된_사진(gallery.galleryId, count = 1).single()
        val token = tokens.generateAccessToken(gallery.member).value
        val base = "/api/v1/galleries/${gallery.galleryId}"

        // when & then
        mvc.put("$base/photos/exclusions") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":[$id]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(1) }
        }
        mvc.get("$base/photos/exclusions?page=0&size=1") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.contents[0].photoId") { value(id) }
            jsonPath("$.contents[0].excludedBy") { value(gallery.member.requiredId) }
            jsonPath("$.contents[0].excludedAt") { isString() }
            jsonPath("$.contents[0].viewUrl") { isString() }
        }
        mvc.get("$base/selection-sync") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.exclusionRevision") { isNumber() }
        }
        mvc.delete("$base/photos/exclusions") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":[$id]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(1) }
        }
    }

    @Test
    fun `모든 제외 경로는 로그인 없이 접근할 수 없다`() {
        // when & then
        val path = "/api/v1/galleries/1/photos/exclusions"
        mvc.get(path).andExpect { status { isUnauthorized() } }
        mvc.put(path) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":[1]}"""
        }.andExpect { status { isUnauthorized() } }
        mvc.delete(path) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":[1]}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `빈 JSON 배치의 오류 계약과 Swagger의 세 메서드를 공개한다`() {
        // given
        val gallery = galleries.멤버와_열린_갤러리()
        val token = tokens.generateAccessToken(gallery.member).value

        // when & then
        mvc.put("/api/v1/galleries/${gallery.galleryId}/photos/exclusions") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":[]}"""
        }.andExpect { status { isBadRequest() } }
        mvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/api/v1/galleries/{galleryId}/photos/exclusions'].get") { exists() }
            jsonPath("$.paths['/api/v1/galleries/{galleryId}/photos/exclusions'].put") { exists() }
            jsonPath("$.paths['/api/v1/galleries/{galleryId}/photos/exclusions'].delete") { exists() }
        }
    }
}
