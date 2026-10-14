package com.soma.wes.studio.domain

import com.soma.wes.global.BaseEntity
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction
import java.time.ZonedDateTime
import java.util.Locale


@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "studios",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_studios_gallery_url", columnNames = ["gallery_url"]),
    ],
)
class Studio(

    /**
     * STUDIO 작업공간과 같은 식별자를 쓰는 공유 PK다. 기존 호출부의 이름만 한시적으로
     * [userId]로 남아 있으며 값의 의미는 사용자 id가 아니라 workspace id다.
     */
    @Id
    @Column(name = "workspace_id", nullable = false, updatable = false)
    val userId: Long,

    @Column(nullable = false, length = 255)
    var name: String,

    @Column(name = "gallery_url", nullable = false, length = 255)
    var galleryUrl: String,

    @Column(name = "inflow_channel", length = 255)
    var inflowChannel: String? = null,

    @Column(length = 100)
    var contact: String? = null,

    @Column(length = 500)
    var description: String? = null,

) : BaseEntity() {

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    @Column(name = "suspended_at")
    var suspendedAt: ZonedDateTime? = null

    val workspaceId: Long
        get() = userId

    val id: Long
        get() = workspaceId

    val requiredId: Long
        get() = workspaceId

    fun update(name: String, galleryUrl: String, contact: String?, description: String?) {
        validateProfile(name, contact, description)
        this.name = name.trim()
        this.galleryUrl = validateGalleryUrl(galleryUrl)
        this.contact = contact
        this.description = description
    }

    companion object {

        fun create(
            userId: Long,
            name: String,
            galleryUrl: String,
            contact: String?,
            description: String?,
        ): Studio {
            validateProfile(name, contact, description)
            return Studio(
                userId = userId,
                name = name.trim(),
                galleryUrl = validateGalleryUrl(galleryUrl),
                contact = contact,
                description = description,
            )
        }

        fun validateProfile(name: String, contact: String?, description: String?) {
            if (name.isBlank() || name.length > 100 || (contact?.length ?: 0) > 100 || (description?.length ?: 0) > 500) {
                throw StudioException(StudioErrorCode.INVALID_STUDIO_PROFILE)
            }
        }

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
