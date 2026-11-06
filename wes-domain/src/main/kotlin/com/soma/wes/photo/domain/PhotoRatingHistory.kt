package com.soma.wes.photo.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

/**
 * 사진 한 장의 별점이 바뀐 기록 한 줄 — 누가, 몇 점으로(지웠으면 null), 언제.
 *
 * [PhotoRating]은 사진당 한 줄을 덮어써서 지나간 점수와 지운 기록이 남지 않는다. 부부가 사진 정보 패널에서
 * "별점이 어떻게 바뀌어 왔나"를 보려면 그 변화를 따로 쌓아야 한다.
 *
 * **[PhotoRating] 행이 실제로 바뀐 커밋에만 한 줄을 쓴다** — 새로 매김·점수 변경·매긴 사람 변경·지움. 같은 사람이
 * 같은 점수를 다시 누르거나 없는 별점을 지우면 남기지 않는다. 그러면 이 행 하나가 `rating_revision` +1 하나와
 * 맞물려, 웹이 변경 번호가 바뀔 때만 다시 읽어도 빠지는 줄이 없다.
 *
 * 쓰는 곳은 `PhotoRatingService` 하나다. 관리자 범용 리소스 편집처럼 서비스를 거치지 않고 `photo_ratings`를
 * 바꾸는 경로는 기록을 남기지 않는다(DB 트리거로 옮기는 안은 지운 사람을 세션 변수로 넘겨야 해서 버렸다).
 *
 * 한 번 쓰고 고치지 않으므로 `BaseEntity`(수정 시각·버전)를 상속하지 않는다.
 */
@Entity
@Table(name = "photo_rating_history")
class PhotoRatingHistory(

    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

    /** null이면 이 시각에 별점을 지웠다. */
    @Column(name = "score", nullable = true, updatable = false)
    val score: Int?,

    /** 매기거나 지운 사람. 지운 줄도 직전에 매긴 사람이 아니라 지운 사람이다. */
    @Column(name = "rated_by", nullable = false, updatable = false)
    val ratedBy: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: ZonedDateTime,

) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "아직 저장되지 않은 별점 기록이다" }
}
