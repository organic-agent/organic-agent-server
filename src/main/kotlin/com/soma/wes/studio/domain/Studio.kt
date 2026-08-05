package com.soma.wes.studio.domain

import com.soma.wes.global.BaseEntity
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 사진작가가 갖는 스튜디오. 갤러리는 작가 개인이 아니라 스튜디오에 속한다.
 *
 * 소셜 로그인 직후 온보딩에서 곧바로 만든다. 작가에게는 스튜디오 생성이 회원가입의 마지막 단계라,
 * 스튜디오 없는 작가 계정은 온보딩을 끝내지 않은 상태다.
 *
 * 작가 한 명이 스튜디오 하나를 갖는다(`UK(user_id)`). "이 사람이 작가인가"와
 * "어느 스튜디오 소속인가"를 이 행 하나가 모두 답하므로 별도 프로필 테이블을 두지 않는다.
 * 한 스튜디오에 작가가 여럿 속하게 되면 그때 `studio_members`를 두고 소속만 그쪽으로 옮긴다.
 */
@Entity
@Table(
    name = "studios",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_studios_user_id", columnNames = ["user_id"]),
        UniqueConstraint(name = "uk_studios_gallery_url", columnNames = ["gallery_url"]),
    ],
)
class Studio(

    /** 스튜디오를 만든 작가. */
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,

    @Column(nullable = false, length = 255)
    var name: String,

    /**
     * 공개 주소의 식별자. 서비스 도메인 바로 아래에 붙으므로(`.../{galleryUrl}`)
     * 스튜디오끼리 겹치면 어느 스튜디오인지 가릴 수 없다.
     */
    @Column(name = "gallery_url", nullable = false, length = 255)
    var galleryUrl: String,

    /** 어디를 통해 들어왔는지. 마케팅 집계용이라 안 받아도 가입은 된다. */
    @Column(name = "inflow_channel", length = 255)
    var inflowChannel: String? = null,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    init {
        validateGalleryUrl(galleryUrl)
    }

    fun isOwnedBy(userId: Long): Boolean = this.userId == userId

    fun update(name: String, galleryUrl: String) {
        validateGalleryUrl(galleryUrl)
        this.name = name
        this.galleryUrl = galleryUrl
    }

    private fun validateGalleryUrl(galleryUrl: String) {
        if (!isValidGalleryUrl(galleryUrl)) {
            throw StudioException(StudioErrorCode.INVALID_GALLERY_URL)
        }
    }

    companion object {

        /**
         * 주소가 쓸 수 있는 모양인지. 온보딩의 중복 확인이 저장 전에 같은 규칙을 물어보려고 쓴다.
         *
         * 규칙을 여기 한 곳에만 두는 것이 요점이다. 확인 API가 자기 정규식을 따로 들고 있으면
         * "확인할 때는 통과했는데 저장이 거절되는" 상황이 생긴다.
         */
        fun isValidGalleryUrl(galleryUrl: String): Boolean =
            GALLERY_URL_FORMAT.matches(galleryUrl) && galleryUrl !in RESERVED_GALLERY_URLS
        /**
         * 소문자·숫자·하이픈만 3~50자. 대문자를 허용하면 대소문자만 다른 주소가 서로 다른
         * 스튜디오로 잡혀 유니크 제약이 무의미해진다.
         */
        private val GALLERY_URL_FORMAT = Regex("^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$")

        /**
         * 서비스가 먼저 쓰는 경로. 도메인 바로 아래에 붙는 주소라, 선점당하면 해당 경로로 못 간다.
         * 새 최상위 경로를 열 때 여기에도 추가해야 한다.
         */
        private val RESERVED_GALLERY_URLS = setOf(
            "api", "admin", "login", "logout", "oauth", "oauth2", "signup", "auth",
            "studio", "studios", "gallery", "galleries", "photo", "photos", "user", "users",
            "actuator", "swagger", "docs", "static", "assets", "public", "www",
        )
    }
}
