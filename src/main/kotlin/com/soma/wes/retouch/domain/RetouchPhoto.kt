package com.soma.wes.retouch.domain

import com.soma.wes.global.BaseEntity
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.time.ZonedDateTime

/**
 * 회차에 담긴 보정 요청 한 건. 요청 내용(텍스트·주석 이미지 key)과 작가의 결과 key를 함께 든다.
 */
@Entity
@SQLRestriction("deleted_at is null")
// 전체 UNIQUE 제약이 아니라 deleted_at IS NULL 부분 유니크 인덱스로 현재 담긴 항목만
// 한 건을 허용한다. JPA는 부분 인덱스를 표현하지 못하므로 기준선 DB 스키마가 계약을 소유한다.
@Table(name = "retouch_photos")
class RetouchPhoto(

    @Column(name = "round_id", nullable = false, updatable = false)
    val roundId: Long,

    /**
     * [roundId]로도 알 수 있는 값이지만 행에 함께 둔다 — 갤러리 스코프 조회(그리드·정리)가
     * 회차를 거치지 않고 항목을 읽는다 ([com.soma.wes.folder.domain.PhotoFolderItem] 전례).
     */
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 사진별 보정 요청 텍스트. 담기만 하고 아직 적지 않았으면 null이다. */
    @Column(name = "request_text")
    var requestText: String? = null

    /** 프론트가 캔버스로 그린 주석 레이어 PNG의 storage key. */
    @Column(name = "annotation_key", length = 500)
    var annotationKey: String? = null

    /** 작가가 올린 보정 결과 파일의 storage key. null이면 아직 응답이 없는 항목이다. */
    @Column(name = "result_key", length = 500)
    var resultKey: String? = null

    @Column(name = "result_content_type", length = 100)
    var resultContentType: String? = null

    /** 관리자 휴지통에 들어간 항목은 모든 사용자 JPA 조회와 변경 경로에서 즉시 제외한다. */
    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null
        protected set

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 RetouchPhoto 다")

    val hasResult: Boolean
        get() = resultKey != null

    /**
     * 요청 내용을 적는다. DRAFTING 동안에는 몇 번이고 덮어쓴다 — 주석은 이미지 방식이라
     * 부분 수정이 없고, 다시 그려 올린 key로 통째로 바뀐다.
     */
    fun writeRequest(requestText: String?, annotationKey: String?) {
        if (requestText != null && requestText.length > MAX_REQUEST_TEXT_LENGTH) {
            throw RetouchException(RetouchErrorCode.REQUEST_TEXT_TOO_LONG)
        }

        this.requestText = requestText
        this.annotationKey = annotationKey
    }

    /**
     * 작가의 결과를 기록한다. 회차가 끝나기 전에는 다시 올린 key로 덮어쓴다 — 결과도 주석처럼
     * 파일 통째 교체라 부분 수정이 없다. key의 소속 검사는 서비스가 한다.
     */
    fun writeResult(resultKey: String, resultContentType: String) {
        this.resultKey = resultKey
        this.resultContentType = resultContentType
    }

    companion object {

        /**
         * 요청 텍스트 상한. 컬럼은 TEXT라 DB가 막지 않으므로 이 값이 유일한 문이다 —
         * 사진 한 장의 보정 지시가 이 길이를 넘으면 그것은 텍스트가 아니라 첨부다.
         */
        const val MAX_REQUEST_TEXT_LENGTH = 2000
    }
}
