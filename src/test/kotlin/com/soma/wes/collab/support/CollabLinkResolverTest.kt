package com.soma.wes.collab.support

import com.soma.wes.collab.config.CollabProperties
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 협업 토큰이 하객에게 보낼 수 있는 링크로 조립되는지 확인한다.
 * 초대 링크와 다른 화면이라 베이스 주소도 따로다 — 섞이면 하객이 로그인 화면을 만난다.
 */
@IntegrationTest
class CollabLinkResolverTest @Autowired constructor(
    private val collabLinkResolver: CollabLinkResolver,
) {

    @Test
    fun `베이스 주소 뒤에 토큰이 붙는다`() {
        // when
        val url = collabLinkResolver.resolve("abc123")

        // then
        assertThat(url).isEqualTo("http://localhost:3000/collab/abc123")
    }

    @Test
    fun `베이스 주소의 마지막 슬래시는 있어도 없어도 같은 링크가 나온다`() {
        // 설정을 어느 쪽으로 적어도 하객이 받는 주소는 하나여야 한다. 슬래시가 겹치면
        // 프론트 라우터가 빈 세그먼트로 읽어 링크가 죽는다.
        // given
        val withTrailingSlash = CollabLinkResolver(CollabProperties(baseUrl = "http://localhost:3000/collab/"))

        // when & then
        assertThat(withTrailingSlash.resolve("abc123")).isEqualTo("http://localhost:3000/collab/abc123")
    }
}
