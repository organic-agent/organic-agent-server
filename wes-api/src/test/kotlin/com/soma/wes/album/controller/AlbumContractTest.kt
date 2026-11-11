package com.soma.wes.album.controller

import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

@IntegrationTest
class AlbumContractTest @Autowired constructor(
    private val mvc: MockMvc,
    private val tokens: AuthTokenProvider,
    private val galleries: GalleryFixture,
) {
    @Test
    fun `웹 문서로 초안을 만들고 인증과 버전 충돌을 HTTP로 처리한다`() {
        // given
        val gallery = galleries.멤버와_열린_갤러리()
        val studioToken = tokens.generateAccessToken(gallery.photographer).value
        val clientToken = tokens.generateAccessToken(gallery.member).value
        val path = "/api/v1/galleries/${gallery.galleryId}/album"
        val layout = """
            {"page":{"w":305,"h":305,"name":"12 × 12"},"spreads":[{"id":"sp1","frames":[]}],
             "guides":[],"assign":{},"crop":{},"gone":{},"cursor":null,"cursorMode":"auto","spread":0}
        """.trimIndent()

        // when & then
        mvc.get(path).andExpect { status { isUnauthorized() } }
        mvc.put(path) {
            header("Authorization", "Bearer $studioToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedRevision":null,"album":$layout}"""
        }.andExpect { status { isBadRequest() } }
        mvc.put(path) {
            header("Authorization", "Bearer $studioToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedRevision":0,"album":$layout}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DRAFT") }
            jsonPath("$.revision") { value(1) }
            jsonPath("$.album.page.name") { value("12 × 12") }
        }
        mvc.put(path) {
            header("Authorization", "Bearer $studioToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedRevision":0,"album":$layout}"""
        }.andExpect { status { isConflict() } }
        mvc.get(path) {
            header("Authorization", "Bearer $clientToken")
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `Swagger에 편집 흐름과 응답 예시가 노출된다`() {
        // when & then
        val path = "/api/v1/galleries/{galleryId}/album"
        mvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['$path'].get.responses['200'].content['application/json'].example.album.assign.fr1") { value(1234) }
            jsonPath("$.paths['$path'].put") { exists() }
            jsonPath("$.paths['$path/photos'].get") { exists() }
            jsonPath("$.paths['$path/send'].post") { exists() }
            jsonPath("$.paths['$path/client-copy'].put") { exists() }
            jsonPath("$.paths['$path/return'].post") { exists() }
            jsonPath("$.paths['$path/confirm'].post") { exists() }
        }
    }
}
