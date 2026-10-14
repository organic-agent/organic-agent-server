package com.soma.wes.photo.domain

import com.soma.wes.global.BaseEntity
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 사진 한 장에 매긴 1~5점의 선호 별점.
 *
 * **사진당 하나다.** 예비 부부 두 사람은 같이 고르는 한 팀이라 각자의 점수를 따로 모아 평균을
 * 내는 것이 화면에서 의미가 없고, 작가도 추천작을 같은 자리에 표시한다. 그래서 누가 매겼는지로
 * 행을 나누지 않고 마지막 사람이 [ratedBy]와 함께 덮어쓴다. 대신 "부부가 5점 준 사진"과
 * "작가가 5점 준 사진"을 갈라 볼 수는 없다 — 그날이 오면 유니크에 축을 하나 더하는 변경이 된다.
 *
 * [Photo]에 컬럼으로 붙이지 않은 것은 댓글·좋아요처럼 사람이 사진에 남기는 것이 계속 늘기
 * 때문이다. 그때마다 `photos`가 넓어지면 수천 장을 훑는 목록 조회가 쓰지도 않는 컬럼을 읽는다.
 */
@Entity
@Table(
    name = "photo_ratings",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_photo_ratings_photo_id", columnNames = ["photo_id"]),
    ],
)
class PhotoRating(

    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

    @Column(nullable = false)
    var score: Int,

    /** 마지막으로 점수를 매긴 사람. 감사용이라 조회 조건으로 쓰지 않는다. */
    @Column(name = "rated_by", nullable = false)
    var ratedBy: Long,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 같은 사진에 다시 매기면 덮어쓴다. 행이 늘지 않는 것이 이 도메인의 전부다. */
    fun rate(score: Int, ratedBy: Long) {
        this.score = requireValidScore(score)
        this.ratedBy = ratedBy
    }

    companion object {
        const val MIN_SCORE = 1
        const val MAX_SCORE = 5

        fun of(photoId: Long, score: Int, ratedBy: Long) = PhotoRating(
            photoId = photoId,
            score = requireValidScore(score),
            ratedBy = ratedBy,
        )

        /**
         * 점수가 들어오는 두 곳([of]·[rate])이 모두 쓴다.
         *
         * 생성자가 아니라 여기에 둔다 — JPA가 DB에서 되살릴 때도 생성자를 지나므로, 거기서
         * 검증하면 어쩌다 잘못 들어간 행 하나가 사진 목록 조회 전체를 실패시킨다.
         * 컨트롤러의 `@Valid`에만 맡기지 않는 이유는 그 검증이 컨트롤러를 지날 때만 돌기 때문이다.
         */
        private fun requireValidScore(score: Int): Int {
            if (score !in MIN_SCORE..MAX_SCORE) {
                throw PhotoException(PhotoErrorCode.INVALID_SCORE)
            }
            return score
        }
    }
}
