package com.soma.wes.retouch.domain

import com.soma.wes.global.BaseEntity
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime
import org.hibernate.annotations.SQLRestriction

/**
 * 보정 회차. 부부가 보정사진을 모아 일괄 제출하는 단위이자, 계약의 "보정 N회"를 세는 단위다.
 *
 * 갤러리당 동시에 하나만 [COMPLETED][RetouchRoundStatus.COMPLETED]가 아닌 상태로 존재한다 —
 * 새 회차는 이전 회차가 끝나야 만들어진다. 그 불변식은 갤러리 행 잠금 위에서 서비스가 지키고,
 * (gallery_id, round_no) 유니크가 마지막으로 막는다.
 */
@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "retouch_rounds",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_retouch_rounds_gallery_round", columnNames = ["gallery_id", "round_no"]),
    ],
)
class RetouchRound(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    /** 1부터 시작하는 회차 번호. 화면과 결과 파일 경로가 이 번호로 회차를 부른다. */
    @Column(name = "round_no", nullable = false, updatable = false)
    val roundNo: Int,

) : BaseEntity() {

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    // allOpen 플러그인이 엔티티를 open으로 만들어 private setter를 쓸 수 없다.
    // 상태 전이는 아래 메서드로만 한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: RetouchRoundStatus = RetouchRoundStatus.DRAFTING

    /** 부부가 제출한 시각. [status]가 REQUESTED가 되는 순간 채워진다. */
    @Column(name = "requested_at")
    var requestedAt: ZonedDateTime? = null

    /** 작가가 회차를 끝낸 시각. */
    @Column(name = "completed_at")
    var completedAt: ZonedDateTime? = null

    /**
     * 회차의 모든 사진에 적용되는 요청("전체 톤 밝게"). 사진 단위 요청([RetouchPhoto.requestText])과 별개로
     * 요청서 맨 앞에 실린다. 적지 않았으면 null이다.
     */
    @Column(name = "request_text")
    var requestText: String? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 RetouchRound 다")

    val isDrafting: Boolean
        get() = status == RetouchRoundStatus.DRAFTING

    /** 회차 전체 요청을 적는다. 제출 전에만 덮어쓰고, 공백뿐인 값은 지운 것으로 본다. */
    fun writeRequestText(text: String?) {
        if (!isDrafting) throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
        if (text != null && text.length > RetouchPhoto.MAX_REQUEST_TEXT_LENGTH) {
            throw RetouchException(RetouchErrorCode.REQUEST_TEXT_TOO_LONG)
        }

        requestText = text?.takeIf { it.isNotBlank() }
    }

    /** 부부가 요청을 일괄 제출한다. 이때부터 작가의 차례라 요청 목록은 잠긴다. */
    fun submit(at: ZonedDateTime) {
        if (status != RetouchRoundStatus.DRAFTING) {
            throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
        }

        status = RetouchRoundStatus.REQUESTED
        requestedAt = at
    }

    /** 작가가 결과를 다 올리고 회차를 끝낸다. 이때부터 다음 회차를 시작할 수 있다. */
    fun complete(at: ZonedDateTime) {
        if (status != RetouchRoundStatus.REQUESTED) {
            throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
        }

        status = RetouchRoundStatus.COMPLETED
        completedAt = at
    }

    /** 작가가 보정 작업을 시작하기 전에 선택을 다시 열면 초안 메모는 보존한다. */
    fun reopenRequest() {
        if (status != RetouchRoundStatus.REQUESTED) throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
        status = RetouchRoundStatus.DRAFTING
        requestedAt = null
    }

    companion object {

        /** 회차 번호의 시작. */
        const val FIRST_ROUND_NO = 1
    }
}
