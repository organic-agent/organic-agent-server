package com.soma.wes.photo.domain

import com.soma.wes.global.BaseEntity
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
import java.time.ZonedDateTime
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLRestriction
import org.hibernate.type.SqlTypes

/**
 * 갤러리에 올라간 사진 한 장.
 *
 * 휴지통에 든 사진([moveToTrash])은 `@SQLRestriction`이 모든 JPA 조회에서 걸러낸다. 목록·상세는
 * 물론 폴더·선택 앨범·협업이 id로 되읽는 경로까지 한 번에 덮기 위해 쿼리가 아니라 엔티티에
 * 선언한다. 휴지통 화면과 복원·물리 삭제는 이 필터를 우회해야 하므로 네이티브 SQL을 쓴다
 * (`trash` 도메인). 네이티브 조회를 새로 만들 때는 `deleted_at IS NULL`을 직접 챙겨야 한다.
 */
@Entity
@SQLRestriction("deleted_at is null")
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

    @Column(name = "preview_key", length = 500)
    var previewKey: String? = null

    @Column(name = "technical_quality_score")
    var technicalQualityScore: Double? = null
        protected set

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "technical_quality_signals", columnDefinition = "jsonb")
    var technicalQualitySignals: Map<String, Any?>? = null
        protected set

    @Column(name = "quality_analyzed_at")
    var qualityAnalyzedAt: ZonedDateTime? = null
        protected set

    /**
     * 이 사진 행을 만들 때 발급한 PUT URL의 실제 만료 시각. null은 URL을 발급하지 않는
     * 복제 사진이다.
     *
     * DB 행을 지워도 URL 자체는 취소되지 않아, 휴지통의 즉시 삭제는 이 시각 전에는 거절된다
     * (만료 purge는 보관 기간이 TTL보다 훨씬 길어 확인이 필요 없다).
     */
    @Column(name = "upload_url_expires_at")
    var uploadUrlExpiresAt: Instant? = null
        protected set

    @Embedded
    var metadata: PhotoMetadata? = null

    /**
     * 휴지통에 들어간 시각. null이면 살아 있는 사진이다.
     *
     * 이 값이 채워지는 순간 `@SQLRestriction` 때문에 어떤 JPA 조회에도 나타나지 않는다.
     * 복원(`deleted_at = NULL`)은 숨은 행을 다뤄야 해서 엔티티가 아니라 trash 도메인의
     * 네이티브 UPDATE가 수행한다.
     */
    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null
        protected set

    fun moveToTrash(at: ZonedDateTime) {
        deletedAt = at
    }

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

    /** [markEmbedded]와 같은 이유로 둔다 — 정상 경로는 Lambda의 UPDATE라 이 메서드를 지나지 않는다. */
    fun applyMetadata(metadata: PhotoMetadata) {
        this.metadata = metadata
    }

    /**
     * 벡터가 [PhotoAnalysis]에 적재됐음을 표시한다. 벡터 자체는 이 엔티티에 없다 — 정상 경로는
     * 임베더 Lambda가 두 테이블을 한 트랜잭션으로 쓰는 것이라 이 메서드를 지나지 않고,
     * Mock 갤러리 복제와 테스트만 쓴다. 호출자가 같은 트랜잭션에서 [PhotoAnalysis]도 저장해야 한다.
     */
    fun markEmbedded() {
        status = PhotoStatus.EMBEDDED
    }

    fun applyTechnicalQuality(score: Double, signals: Map<String, Any?>, analyzedAt: ZonedDateTime) {
        require(score in 0.0..100.0) { "technical quality score must be between 0 and 100" }
        technicalQualityScore = score
        technicalQualitySignals = signals.toMap()
        qualityAnalyzedAt = analyzedAt
    }

    companion object {

        /**
         * 화면 순서는 갤러리에서 정한 노출 순서를 따른다. 같으면 id로 한 번 더 갈라, 같은
         * 배치로 발급돼 displayOrder가 겹치는 사진들이 화면마다 자리를 바꾸지 않게 한다.
         * 메모리에서 사진을 정렬하는 모든 화면(폴더·선택 앨범)이 이 하나를 쓴다.
         */
        val DISPLAY_ORDER = compareBy<Photo>({ it.displayOrder }, { it.requiredId })

        /**
         * 임베딩까지 끝난 사진을 다른 갤러리로 복제한 행을 만든다. Mock 갤러리가 템플릿
         * 갤러리의 사진을 가져올 때 쓴다.
         *
         * [storageKey]·[previewKey]는 호출자가 새 갤러리의 키 공간으로 복사해 둔 S3 위치다 —
         * 원본 키를 그대로 넘기면 두 행이 한 객체를 참조해 storage_key 전역 유니크에 걸린다.
         * 촬영 정보는 값을 새로 떠서 담는다. detached 원본과 인스턴스를 나눠 가지면 한쪽 상태
         * 변경이 다른 엔티티에 새어 들어간다. 벡터는 [PhotoAnalysis]에 있으므로 호출자가 따로
         * 복제한다 — 그래서 여기서는 [markEmbedded]만 한다.
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
            val qualityScore = source.technicalQualityScore
            val qualitySignals = source.technicalQualitySignals
            val qualityAnalyzedAt = source.qualityAnalyzedAt
            if (qualityScore != null && qualitySignals != null && qualityAnalyzedAt != null) {
                copy.applyTechnicalQuality(qualityScore, qualitySignals, qualityAnalyzedAt)
            }
            copy.markEmbedded()
        }
    }
}
