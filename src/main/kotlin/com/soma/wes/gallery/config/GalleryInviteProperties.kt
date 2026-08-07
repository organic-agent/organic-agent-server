package com.soma.wes.gallery.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 초대 링크가 가리키는 곳.
 *
 * 서버가 서는 주소가 아니라 **프론트 주소**다. 링크를 받은 사람이 여는 것은 API가 아니라
 * 수락 화면이고, 그 화면이 로그인 여부를 확인한 뒤 수락 API를 부른다.
 */
@ConfigurationProperties(prefix = "app.invite")
data class GalleryInviteProperties(

    /**
     * 토큰 앞에 붙는 부분. 마지막 슬래시는 있어도 없어도 된다.
     *
     * 배포 도메인에 종속되므로 prod는 Parameter Store가 덮어쓴다.
     */
    val baseUrl: String,
)
