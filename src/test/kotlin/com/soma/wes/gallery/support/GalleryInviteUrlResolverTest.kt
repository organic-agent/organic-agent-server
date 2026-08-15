package com.soma.wes.gallery.support

import com.soma.wes.gallery.config.GalleryInviteProperties
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 초대 링크가 "베이스 + 토큰"으로 조립되는지 확인한다.
 *
 * 베이스는 서버가 아니라 프론트 주소라 프로퍼티(`app.invite.base-url`)에서 온다 —
 * 첫 테스트는 그 바인딩까지 함께 확인하므로 테스트 설정의 값을 그대로 기대값에 쓴다.
 */
@IntegrationTest
class GalleryInviteUrlResolverTest @Autowired constructor(
    private val galleryInviteUrlResolver: GalleryInviteUrlResolver,
) {

    @Test
    fun `베이스 URL 뒤에 토큰을 붙여 조립한다`() {
        // when
        val url = galleryInviteUrlResolver.resolve("invite-token-abc")

        // then
        // src/test/resources/application.yml의 app.invite.base-url 값이다.
        assertThat(url).isEqualTo("http://localhost:3000/invite/invite-token-abc")
    }

    @Test
    fun `베이스 URL 끝에 슬래시가 있어도 겹치지 않는다`() {
        // 프로퍼티 KDoc의 약속 — 마지막 슬래시는 있어도 없어도 된다. 운영자가 어느 쪽으로
        // 넣든 `//token`이 되지 않아야 한다.
        // given
        val resolver = GalleryInviteUrlResolver(GalleryInviteProperties(baseUrl = "http://localhost:3000/invite/"))

        // when
        val url = resolver.resolve("invite-token-abc")

        // then
        assertThat(url).isEqualTo("http://localhost:3000/invite/invite-token-abc")
    }
}
