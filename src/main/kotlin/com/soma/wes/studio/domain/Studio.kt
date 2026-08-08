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

    init {
        galleryUrl = normalizeGalleryUrl(galleryUrl)
        validateGalleryUrl(galleryUrl)
    }

    fun isOwnedBy(userId: Long): Boolean = this.userId == userId

    fun update(name: String, galleryUrl: String) {
        val normalizedGalleryUrl = normalizeGalleryUrl(galleryUrl)
        validateGalleryUrl(normalizedGalleryUrl)
        this.name = name
        this.galleryUrl = normalizedGalleryUrl
    }

    private fun validateGalleryUrl(galleryUrl: String) {
        if (!isValidGalleryUrl(galleryUrl)) {
            throw StudioException(StudioErrorCode.INVALID_GALLERY_URL)
        }
    }

    companion object {

        fun normalizeGalleryUrl(galleryUrl: String): String {
            val trimmedGalleryUrl = galleryUrl.trim()
            if (trimmedGalleryUrl.any { it.code > ASCII_MAX_CODE_POINT }) {
                throw StudioException(StudioErrorCode.INVALID_GALLERY_URL)
            }

            return trimmedGalleryUrl.lowercase(Locale.ROOT)
        }

        fun isValidGalleryUrl(galleryUrl: String): Boolean =
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
