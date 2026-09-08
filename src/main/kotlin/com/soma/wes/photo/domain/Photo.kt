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
import org.hibernate.annotations.SQLRestriction

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

    /**
     * 임베더에게 이 사진을 보낸 시각. 스윕이 UPLOADED·벡터 없음·null 인 사진을 배치로 집을 때 찍고,
     * 벡터가 오지 않은 채 오래되면 null로 되돌려 다시 보낸다. 임베더는 일시 실패한 장만 null로 되돌린다.
     * 값을 쓰는 곳은 배치 SQL([com.soma.wes.photo.repository.PhotoPipelineRepository])이라 엔티티에는 읽기만 있다 —
     * JPA가 UPDATE에 이 컬럼을 넣으면 오래된 스냅샷이 스윕의 배정을 덮으므로 매핑에서 쓰기를 막는다.
     */
    @Column(name = "dispatched_at", insertable = false, updatable = false)
    val dispatchedAt: ZonedDateTime? = null

    /** 임베더에게 보낸 횟수. 상한에 닿으면 [PhotoAnalysis.error]에 실패로 남기고 더 보내지 않는다. 배치 SQL이 올린다. */
    @Column(name = "embed_attempts", nullable = false, insertable = false, updatable = false)
    val embedAttempts: Int = 0

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

    /** 원본이 S3에 있음을 표시한다. 프론트의 완료 통보와 서버의 HeadObject 보정이 부르며, 재통보는 멱등이다. */
    fun markUploaded() {
        status = PhotoStatus.UPLOADED
    }

    /** 정상 경로는 임베더 Lambda의 UPDATE라 이 메서드를 지나지 않는다 — Mock 갤러리 복제와 테스트가 쓴다. */
    fun applyMetadata(metadata: PhotoMetadata) {
        this.metadata = metadata
    }

    companion object {

        /**
         * S3 `x-amz-checksum-crc32c` 헤더의 형식 — CRC32C 4바이트를 base64로 적은 8자(끝은 항상 `==`).
         * 발급 요청이 이 값을 가져오고 서명에 그대로 들어가므로, 형식이 틀리면 발급 단계에서 막는다.
         */
        const val CRC32C_BASE64_PATTERN = "^[A-Za-z0-9+/]{6}==$"

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
         * 복제한다 — 여기서는 원본이 있다는 표시([markUploaded])만 한다.
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
            copy.markUploaded()
        }
    }
}
