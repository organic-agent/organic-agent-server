package com.soma.wes.photo.domain

import com.soma.wes.global.BaseEntity
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import org.hibernate.annotations.Array
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 갤러리에 올라간 사진 한 장.
 */
@Entity
@Table(
    name = "photos",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_photos_storage_key", columnNames = ["storage_key"]),
    ],
    indexes = [
        Index(name = "idx_photos_gallery_id", columnList = "gallery_id"),
        Index(name = "idx_photos_gallery_id_status", columnList = "gallery_id, status"),
    ],
)
class Photo(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    /** 오브젝트 스토리지에서의 위치. 사진이 다른 갤러리로 옮겨가는 일은 없으므로 변경하지 않는다. */
    @Column(name = "storage_key", nullable = false, updatable = false, length = 500)
    val storageKey: String,

    @Column(name = "original_file_name", nullable = false, updatable = false, length = 255)
    val originalFileName: String,

    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    val contentType: String,

    /** 갤러리 안에서의 노출 순서. 작가가 정렬을 바꿀 수 있다. */
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PhotoStatus = PhotoStatus.PENDING

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSION)
    @Column(name = "embedding")
    var embedding: FloatArray? = null

    @Column(name = "preview_key", length = 500)
    var previewKey: String? = null

    @Embedded
    var metadata: PhotoMetadata? = null

    @Column(name = "upload_url_expires_at")
    var uploadUrlExpiresAt: Instant? = null
        protected set

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 Photo 다")

    val viewKey: String
        get() = previewKey ?: storageKey

    fun changeDisplayOrder(displayOrder: Int) {
        this.displayOrder = displayOrder
    }

    fun recordUploadUrlExpiration(expiresAt: Instant) {
        uploadUrlExpiresAt = expiresAt
    }

    fun markUploaded() {
        if (status == PhotoStatus.EMBEDDED) {
            return
        }
        status = PhotoStatus.UPLOADED
    }

    /** [embedding]과 같은 이유로 둔다 — 정상 경로는 Lambda의 UPDATE라 이 메서드를 지나지 않는다. */
    fun applyMetadata(metadata: PhotoMetadata) {
        this.metadata = metadata
    }

    fun applyEmbedding(vector: FloatArray) {
        if (vector.size != EMBEDDING_DIMENSION) {
            throw PhotoException(PhotoErrorCode.EMBEDDING_DIMENSION_MISMATCH)
        }
        embedding = vector
        status = PhotoStatus.EMBEDDED
    }

    companion object {

        const val EMBEDDING_DIMENSION = 768

        /**
         * 임베딩까지 끝난 사진을 다른 갤러리로 복제한 행을 만든다. Mock 갤러리가 템플릿
         * 갤러리의 사진을 가져올 때 쓴다.
         *
         * [storageKey]·[previewKey]는 호출자가 새 갤러리의 키 공간으로 복사해 둔 S3 위치다 —
         * 원본 키를 그대로 넘기면 두 행이 한 객체를 참조해 storage_key 전역 유니크에 걸린다.
         * 벡터와 촬영 정보는 값을 새로 떠서 담는다. detached 원본과 인스턴스를 나눠 가지면
         * 한쪽 상태 변경이 다른 엔티티에 새어 들어간다.
         */
        fun copyOf(
            source: Photo,
            galleryId: Long,
            storageKey: String,
            previewKey: String?,
            displayOrder: Int,
        ): Photo = Photo(
            galleryId = galleryId,
            storageKey = storageKey,
            originalFileName = source.originalFileName,
            contentType = source.contentType,
            displayOrder = displayOrder,
        ).also { copy ->
            copy.previewKey = previewKey
            source.metadata?.let { metadata ->
                copy.applyMetadata(
                    PhotoMetadata(
                        takenAt = metadata.takenAt,
                        cameraMake = metadata.cameraMake,
                        cameraModel = metadata.cameraModel,
                        exposureTime = metadata.exposureTime,
                        fNumber = metadata.fNumber,
                        iso = metadata.iso,
                        width = metadata.width,
                        height = metadata.height,
                        byteSize = metadata.byteSize,
                    ),
                )
            }
            copy.applyEmbedding(
                checkNotNull(source.embedding) { "임베딩이 없는 사진은 복제할 수 없습니다." }.copyOf(),
            )
        }
    }
}
