package com.soma.wes.auth.strategy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OAuthProfileImageTest {
    @Test
    fun `각 소셜 공급자의 실제 응답 필드에서 사진을 읽는다`() {
        val image = "https://images.example.com/avatar.jpg"
        assertThat(GoogleUserInfoExtractor().extract(mapOf("sub" to "google", "picture" to image)).profileImageUrl)
            .isEqualTo(image)
        assertThat(KakaoUserInfoExtractor().extract(mapOf(
            "id" to 123, "kakao_account" to mapOf("profile" to mapOf("profile_image_url" to image)),
        )).profileImageUrl).isEqualTo(image)
        assertThat(NaverUserInfoExtractor().extract(mapOf(
            "response" to mapOf("id" to "naver", "profile_image" to image),
        )).profileImageUrl).isEqualTo(image)
    }

    @Test
    fun `사진 제공에 동의하지 않은 사용자도 로그인 정보를 읽을 수 있다`() {
        assertThat(GoogleUserInfoExtractor().extract(mapOf("sub" to "google")).profileImageUrl).isNull()
        assertThat(KakaoUserInfoExtractor().extract(mapOf("id" to 123)).profileImageUrl).isNull()
        assertThat(NaverUserInfoExtractor().extract(mapOf("response" to mapOf("id" to "naver"))).profileImageUrl).isNull()
    }

    @Test
    fun `비정상 사진 값은 로그인 전체를 실패시키지 않고 제외한다`() {
        listOf("", "  ", "javascript:alert(1)", "file:///tmp/photo.jpg", "//images.example.com/p.jpg",
            "https://user:password@example.com/p.jpg", "https://example.com/" + "x".repeat(2048), 123)
            .forEach { invalid ->
                val info = GoogleUserInfoExtractor().extract(mapOf("sub" to "google", "picture" to invalid))
                assertThat(info.providerId).isEqualTo("google")
                assertThat(info.profileImageUrl).describedAs("invalid photo: %s", invalid).isNull()
            }
        assertThat(GoogleUserInfoExtractor().extract(mapOf(
            "sub" to "google", "picture" to "  https://images.example.com/p.jpg  ",
        )).profileImageUrl).isEqualTo("https://images.example.com/p.jpg")
    }
}
