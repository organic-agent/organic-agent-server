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
import org.hibernate.annotations.Array
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 갤러리에 올라간 사진 한 장.
 *
 * 실제 파일은 DB가 아니라 오브젝트 스토리지에 있고, 여기에는 그 위치([storageKey])만 둔다.
 * 바이트는 서버를 거치지 않는다 — 프론트가 서명 URL로 S3에 직접 올리고, 서버는 목적지를
 * 정해주고([PENDING][PhotoStatus.PENDING]) 완료를 통보받는다([markUploaded]).
 *
 * [embedding]도 이 서버가 채우지 않는다. 갤러리 단위로 깨어난 Lambda가 직접 UPDATE 한다.
 * [applyEmbedding]은 그 경로를 쓰지 않는 테스트와, 나중에 결과를 API로 받게 될 경우를 위해 둔다.
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

    /**
     * 업로드 URL에 함께 서명되는 값. 프론트가 PUT 할 때 같은 값을 보내야 서명이 맞고,
     * 나중에 브라우저가 내려받을 때 S3가 이 값을 그대로 돌려준다.
     */
    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    val contentType: String,

    /** 갤러리 안에서의 노출 순서. 작가가 정렬을 바꿀 수 있다. */
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    // allOpen 플러그인이 엔티티를 open으로 만들어 private setter를 쓸 수 없다.
    // 상태 전이는 아래 메서드로만 한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PhotoStatus = PhotoStatus.PENDING

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSION)
    @Column(name = "embedding")
    var embedding: FloatArray? = null

    /**
     * 브라우저가 그릴 수 있는 파생 JPEG의 위치. [embedding]과 마찬가지로 이 서버가 아니라
     * 임베딩 Lambda가 채운다 — 그쪽이 이미 원본을 디코딩해 들고 있다.
     *
     * null은 "아직 파생본이 없다"는 뜻이다. 업로드 완료와 임베딩 실행 사이가 그렇고,
     * 파생본 업로드만 실패한 사진도 여기 null로 남는다.
     */
    @Column(name = "preview_key", length = 500)
    var previewKey: String? = null

    /**
     * 촬영 정보(EXIF). [embedding]·[previewKey]와 마찬가지로 임베딩 Lambda가 채운다 —
     * 이 서버는 이미지 바이트를 만지지 않아 EXIF를 읽을 방법이 없다.
     *
     * 컬럼이 전부 비어 있으면 Hibernate가 null을 넣는다. 아직 Lambda가 돌지 않았거나,
     * 원본에 EXIF가 없고 크기조차 읽지 못한 경우다.
     */
    @Embedded
    var metadata: PhotoMetadata? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 Photo 다")

    /**
     * 화면에 그릴 객체의 위치.
     *
     * 파생본이 있으면 그쪽이다. 원본은 HEIC일 수 있어서 브라우저가 그리지 못할 뿐 아니라,
     * 목록에 수백 장을 띄우기에는 그대로 내려주기에 너무 크다.
     */
    val viewKey: String
        get() = previewKey ?: storageKey

    fun changeDisplayOrder(displayOrder: Int) {
        this.displayOrder = displayOrder
    }

    /**
     * 프론트가 S3 업로드를 마쳤다고 알려올 때.
     *
     * 이미 EMBEDDED면 건드리지 않는다. 지금은 임베딩이 완료 통보 뒤에 오므로 이 경우가 생기지
     * 않지만, 트리거를 S3 이벤트 같은 것으로 바꾸는 순간 완료 통보가 뒤늦게 도착해 방금 채운
     * EMBEDDED를 UPLOADED로 되돌린다. 그러면 벡터는 멀쩡한데 집계만 틀리는, 증상이 원인을
     * 가리키지 않는 상태가 된다.
     */
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
        /**
         * `photos.embedding`의 `vector(n)`과 반드시 같아야 한다. DINOv2-base의 CLS 토큰
         * 차원이고, 임베딩 Lambda의 `EMBED_DIM` 환경변수(인프라의 `embedding_dimension` 변수)도
         * 같은 값이다. 모델을 바꿔 차원이 달라지면 셋을 함께 옮겨야 한다 — 어긋나면 UPDATE가
         * DB에서 거절되므로 조용히 틀리지는 않는다.
         */
        const val EMBEDDING_DIMENSION = 768
    }
}
