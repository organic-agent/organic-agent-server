package com.soma.wes.security

import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.collab.fixture.CollabFixture
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

/** 수동 폴더의 nullable 필드, DELETE 본문, 부분 PATCH를 실제 인증 필터와 JSON 경계로 검증한다. */
@IntegrationTest
class SharedFolderApiContractTest @Autowired constructor(
    private val mvc: MockMvc,
    private val tokens: AuthTokenProvider,
    private val galleries: GalleryFixture,
    private val collab: CollabFixture,
    private val photos: PhotoFixture,
    private val sessions: CollabSessionRepository,
) {
    @Test
    fun `이름만 생성하고 사진 추가 제거와 표지 부분 변경을 JSON으로 주고받는다`() {
        // given
        val fixture = galleries.멤버와_열린_갤러리()
        val photoIds = photos.업로드된_사진(fixture.galleryId, 2)
        val token = tokens.generateAccessToken(fixture.member).value
        val base = "/api/v1/galleries/${fixture.galleryId}/collab-sessions"
        // when & then
        mvc.post(base) {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"직접 공유"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.selectionMode") { value("MANUAL") }
            jsonPath("$.conceptFolderId") { doesNotExist() }
            jsonPath("$.photoCount") { value(0) }
            jsonPath("$.includeAllAlbums") { value(false) }
        }
        val sessionId = sessions.findAll().single().requiredId
        mvc.post("$base/$sessionId/photos") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":[${photoIds.joinToString(",")}]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.photoCount") { value(2) }
        }
        mvc.delete("$base/$sessionId/photos") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":[${photoIds.first()}]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.photoCount") { value(1) }
        }
        mvc.patch("$base/$sessionId") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"coverTitle":"함께 고르는 사진","coverAuthor":"우리","includeAllAlbums":true}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("직접 공유") }
            jsonPath("$.coverTitle") { value("함께 고르는 사진") }
            jsonPath("$.coverAuthor") { value("우리") }
            jsonPath("$.includeAllAlbums") { value(true) }
        }
        val collabToken = sessions.findById(sessionId).orElseThrow().collabToken
        mvc.get("/api/v1/collab/$collabToken").andExpect {
            status { isOk() }
            jsonPath("$.albums[0].sessionId") { value(sessionId) }
            jsonPath("$.albums[0].conceptFolderId") { doesNotExist() }
            jsonPath("$.photoCount") { value(1) }
        }
    }

    @Test
    fun `컨셉으로 만든 공유폴더도 전환 없이 바로 사진을 담는다`() {
        // given
        val fixture = collab.사진이_있는_세션()
        val token = tokens.generateAccessToken(fixture.gallery.member).value
        val base = "/api/v1/galleries/${fixture.galleryId}/collab-sessions/${fixture.session.sessionId}"
        // when & then
        mvc.post("$base/photos") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":[${fixture.photoId}]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.selectionMode") { value("MANUAL") }
            jsonPath("$.conceptFolderId") { doesNotExist() }
            jsonPath("$.collabUrl") { value(fixture.session.collabUrl) }
            jsonPath("$.photoCount") { value(1) }
        }
    }
}
