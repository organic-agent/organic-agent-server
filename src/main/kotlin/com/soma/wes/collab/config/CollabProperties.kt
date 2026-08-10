package com.soma.wes.collab.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 협업 링크가 가리키는 곳.
 *
 * 초대 링크(`app.invite.base-url`)와 마찬가지로 **프론트 주소**이며, 그쪽과 다른 화면이라 값도
 * 따로 둔다. 초대 링크를 여는 화면은 로그인을 요구하지만 협업 링크를 여는 화면은 요구하면
 * 안 된다 — 로그인하지 않는 하객을 위해 만든 것이 이 링크다.
 */
@ConfigurationProperties(prefix = "app.collab")
data class CollabProperties(

    /**
     * 토큰 앞에 붙는 부분. 마지막 슬래시는 있어도 없어도 된다.
     *
     * 배포 도메인에 종속되므로 prod는 Parameter Store가 덮어쓴다.
     */
    val baseUrl: String,
)
