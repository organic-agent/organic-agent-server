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
 * 이 행 하나가 사진의 분석 진행을 말한다 — [embedding](임베더) → [clipEmbedding]·[subScores](score) →
 * [technicalPct]·[embedGroupId](categorize), 실패는 [error]. 사진 쪽 상태([Photo.status])는 "S3에 있나"만 답한다.
 *
 * 행은 임베더가 첫 배치에서 `INSERT … ON CONFLICT`로 만든다 — 이 서버는 미리 빈 행을 만들지 않고, 진행을 셀 때는
 * LEFT JOIN으로 없는 행을 "아직"으로 읽는다. 행을 지우는 것(재분석 리셋)은 이 서버의 일이고, 컬럼 값은 Lambda·GPU 워커
 * 소유라 읽기 전용 `val`이다. [embeddedBy]는 Mock 갤러리 복제와 테스트가 쓰는 우회로다. `face_boxes`(jsonb)는 이 서버가
 * 읽지 않아 매핑하지 않았다.
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
     * 임베딩 그룹 번호 — DINOv3·CLIP 벡터를 이어 붙인 공간에서 AI가 묶은 사진 덩어리. 갤러리 안에서만 유일하고, -1은 미배정(임베딩 없음)이다.
     * 화면에 나오지 않는 내부 단위다 — 컨셉 배정([ConceptAssignment])과 폴더 상세 정렬이 쓴다.
     */
    @Column(name = "embed_group_id")
    val embedGroupId: Int? = null

    /** CLIP zero-shot 피사체(신부/신랑/두 분/단체). 자식 폴더 category의 재료. */
    @Column(name = "subjects", length = 20)
    val subjects: String? = null

    /** 갤러리 안 백분위 0~100. 원점수가 아니라 모델마다 단위가 달라도 비교할 수 있다. CATEGORIZE 잡이 쓴다. */
    @Column(name = "technical_pct")
    val technicalPct: Float? = null

    @Column(name = "aesthetic_pct")
    val aestheticPct: Float? = null

    // [GLOSSARY-1 2026-09-27] clusterId → burstId, clusterRank → burstRank (용어집: 연사). 컬럼 이름은 용어 2단계에서 바꾼다.
    /** 연사 번호 — 같은 카메라에서 거의 같은 순간 연속으로 찍힌 사진 묶음. 갤러리 안에서만 유일하다. */
    @Column(name = "cluster_id")
    val burstId: Int? = null

    /** 연사 안 순위. 0이 연사 대표다. */
    @Column(name = "cluster_rank")
    val burstRank: Int? = null

    // [GLOSSARY-1 2026-09-27] modelVersion → pipelineVersion (용어집 D3: 모델 id가 아니라 파이프라인 버전). 컬럼 이름은 용어 2단계에서 바꾼다.
    /** 분석 컬럼을 만든 파이프라인 버전(예: `photoselect-v3-a-0.1`). score 단계가 쓴다 — null이면 임베딩만 있고 분석은 아직이다. */
    @Column(name = "model_version", length = 40)
    val pipelineVersion: String? = null

    @Column(name = "analyzed_at")
    val analyzedAt: ZonedDateTime? = null

    /**
     * 이 사진의 분석이 결정적으로 실패한 이유. 임베더(디코드 불가)·score(미리보기 없음)·이 서버(재시도 상한)가 쓰고,
     * 채워진 사진은 배정·집기·기대 장수에서 빠진다. 사진 상태에 실패 값을 두지 않는 대신 여기 하나로 드러낸다.
     */
    @Column(name = "error")
    val error: String? = null

    /**
     * 분석 배치의 세부 점수(`sharpness`·`sharpness_pct`·`highlight_clip`·`shadow_clip`·`technical_score`·
     * `aesthetic_score` …). 추천 이유 문장의 재료다. 행을 만들 때는 빈 객체다(DB 기본값과 같다).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sub_scores", nullable = false, columnDefinition = "jsonb")
    val subScores: Map<String, Any?> = emptyMap()

    /** 숫자 세부 점수 하나. 없거나 숫자가 아니면 null — 배치 버전에 따라 키가 빠질 수 있다. */
    fun subScore(key: SubScoreKey): Double? = (subScores[key.key] as? Number)?.toDouble()

    /**
     * 분석 완료 — 폴더·추천이 재료로 써도 되는 행인가. 배치가 score(`model_version`)와 categorize(백분위·그룹)
     * 두 단계로 갈라져 있어 `model_version`만으로는 부족하다. 그 사이 창의 행은 백분위가 비어 있고, 그런 행을
     * 완료로 보면 추천이 기본값으로 조용히 틀린다. 소비자가 실제로 쓰는 백분위가 채워졌는지를 본다.
     */
    val isAnalyzed: Boolean
        get() = pipelineVersion != null && technicalPct != null && aestheticPct != null

    val isFailed: Boolean
        get() = error != null

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
