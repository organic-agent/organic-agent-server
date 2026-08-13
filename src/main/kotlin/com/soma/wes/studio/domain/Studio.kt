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
import java.util.Locale


@Entity
@Table(
    name = "studios",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_studios_user_id", columnNames = ["user_id"]),
        UniqueConstraint(name = "uk_studios_gallery_url", columnNames = ["gallery_url"]),
    ],
)
class Studio(

    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,

    @Column(nullable = false, length = 255)
    var name: String,

    @Column(name = "gallery_url", nullable = false, length = 255)
    var galleryUrl: String,

    @Column(name = "inflow_channel", length = 255)
    var inflowChannel: String? = null,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    fun update(name: String, galleryUrl: String) {
        this.name = name
        this.galleryUrl = validateGalleryUrl(galleryUrl)
    }

    companion object {

        fun create(userId: Long, name: String, galleryUrl: String, inflowChannel: String? = null) =
            Studio(
                userId = userId,
                name = name,
                galleryUrl = validateGalleryUrl(galleryUrl),
                inflowChannel = inflowChannel
            )

        /**
         * 주소가 밖에서 들어오는 모든 곳([of]·[update]·주소 확인)이 쓴다.
         */
        fun validateGalleryUrl(galleryUrl: String): String {
            val normalizedGalleryUrl = normalizeGalleryUrl(galleryUrl)
            if (!checkGalleryUrl(normalizedGalleryUrl)) {
                throw StudioException(StudioErrorCode.INVALID_GALLERY_URL)
            }
            return normalizedGalleryUrl
        }

        fun normalizeGalleryUrl(galleryUrl: String): String {
            if (galleryUrl.any { it.code > ASCII_MAX_CODE_POINT }) {
                throw StudioException(StudioErrorCode.INVALID_GALLERY_URL)
            }

            return galleryUrl.trim().lowercase(Locale.ROOT) // 어떤 환경에서든 유니코드 기본 매핑 규칙만 적용
        }

        fun checkGalleryUrl(galleryUrl: String): Boolean =
            GALLERY_URL_FORMAT.matches(galleryUrl) && galleryUrl !in RESERVED_GALLERY_URLS

        private val GALLERY_URL_FORMAT = Regex("^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$")

        private const val ASCII_MAX_CODE_POINT = 0x7F

        private val RESERVED_GALLERY_URLS = setOf(
            "api", "admin", "login", "logout", "oauth", "oauth2", "signup", "auth",
            "studio", "studios", "gallery", "galleries", "photo", "photos", "user", "users",
            "actuator", "swagger", "docs", "static", "assets", "public", "www",
        )
    }
}
