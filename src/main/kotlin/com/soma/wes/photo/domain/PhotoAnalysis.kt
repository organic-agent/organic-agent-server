package com.soma.wes.photo.domain

import com.soma.wes.global.BaseEntity
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime
import org.hibernate.annotations.Array
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 사진 한 장의 모델 파생값. [Photo]와 1:1이고 `photo_id`가 곧 키다.
 *
 * [Photo]에서 떼어 둔 이유는 생명주기다. 정체성(storage_key·EXIF)은 업로드 때 한 번 정해지지만
 * 임베딩·태그·점수·클러스터는 모델이 바뀔 때마다 갤러리 단위로 통째 다시 적는다. 목록 조회가
 * 768차원 벡터를 읽지 않게 하는 효과도 같다(`PhotoRating`과 같은 이유).
 *
 * 행 자체는 업로드 확정(PhotoService.completeUpload)이 빈 값으로 만든다 — 행의 존재·생명주기는
 * 이 서버 소유, 컬럼 값은 Lambda 소유(잡 테이블과 같은 규약). 값을 쓰는 주체는 둘이다.
 * 임베더 Lambda가 [embedding]·[embeddingModel]을 `INSERT … ON CONFLICT`로
 * 채우고, AI 분석 배치(full 잡)가 그룹·피사체·점수·클러스터를 채운다. 이 서버는 두 값 모두 정상
 * 경로에서는 쓰지 않는다 — [embeddedBy]는 Mock 갤러리 복제와 테스트가 쓰는 우회로다. 분석 컬럼은
 * 읽기 전용이라 `val`이고, `face_boxes`·`sub_scores`(jsonb)는 이 서버가 읽지 않아 매핑하지 않았다.
 */
@Entity
@Table(name = "photo_analysis")
class PhotoAnalysis(

    @Id
    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

) : BaseEntity() {

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSION)
    @Column(name = "embedding")
    var embedding: FloatArray? = null
        protected set

    /** 벡터를 만든 모델 id(`facebook/dinov3-vitb16-pretrain-lvd1689m`). 모델을 바꾸면 이 값으로 재계산 대상을 가른다. */
    @Column(name = "embedding_model", length = 60)
    var embeddingModel: String? = null
        protected set

    /**
     * CLIP ViT-L/14 벡터. naming 잡이 소그룹 최근접 배정·부모 검증에 쓴다 — 사진 재분석 없이
     * naming 잡만 다시 돌리기 위해 저장한다. 이 서버는 매핑만 하고 읽지 않는다.
     */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSION)
    @Column(name = "clip_embedding")
    val clipEmbedding: FloatArray? = null

    /**
     * 임베딩 그룹(concat 계층 클러스터) 번호. 갤러리 안에서만 유일하고, -1은 미배정(임베딩 없음)이다.
     * 화면에 나오지 않는 내부 단위다 — 컨셉 배정([AiConceptAssignment])과 폴더 상세 정렬이 쓴다.
     */
    @Column(name = "embed_group_id")
    val embedGroupId: Int? = null

    /** CLIP zero-shot 피사체(신부/신랑/두 분/단체). 자식 폴더 category의 재료. */
    @Column(name = "subjects", length = 20)
    val subjects: String? = null

    /** 갤러리 안 백분위 0~100. 원점수가 아니라 모델마다 단위가 달라도 비교할 수 있다. */
    @Column(name = "technical_pct")
    val technicalPct: Float? = null

    @Column(name = "aesthetic_pct")
    val aestheticPct: Float? = null

    /** 근접 중복(연사) 클러스터 번호. 갤러리 안에서만 유일하다. */
    @Column(name = "cluster_id")
    val clusterId: Int? = null

    /** 클러스터 안 순위. 0이 대표다. */
    @Column(name = "cluster_rank")
    val clusterRank: Int? = null

    /** 분석 컬럼을 만든 파이프라인 버전. null이면 임베딩만 있고 분석은 아직이다. */
    @Column(name = "model_version", length = 40)
    val modelVersion: String? = null

    @Column(name = "analyzed_at")
    val analyzedAt: ZonedDateTime? = null

    val isAnalyzed: Boolean
        get() = modelVersion != null

    companion object {

        /**
         * DINOv3 ViT-B/16의 CLS 토큰 차원(DINOv2-base와 같다). 마이그레이션의 `vector(768)`, 인프라 repo의
         * `embedding_dimension`과 세 곳이 일치해야 한다 (`.claude/rules/migration.md`).
         */
        const val EMBEDDING_DIMENSION = 768

        /**
         * 벡터만 담긴 행을 만든다. Mock 갤러리가 템플릿 사진의 벡터를 복제할 때와 테스트가 쓴다.
         * 정상 경로는 임베더 Lambda의 SQL이라 이 팩토리를 지나지 않는다.
         *
         * [vector]는 호출자가 값을 새로 뜬 것이어야 한다 — 원본 엔티티와 배열을 나눠 가지면 한쪽의
         * 변경이 다른 엔티티에 새어 들어간다.
         */
        fun embeddedBy(photoId: Long, vector: FloatArray, model: String): PhotoAnalysis {
            if (vector.size != EMBEDDING_DIMENSION) {
                throw PhotoException(PhotoErrorCode.EMBEDDING_DIMENSION_MISMATCH)
            }
            return PhotoAnalysis(photoId = photoId).also {
                it.embedding = vector
                it.embeddingModel = model
            }
        }
    }
}
