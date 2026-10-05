package com.soma.wes.folder.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import java.time.ZonedDateTime

/**
 * 합치기가 옮긴 사진 한 장과, 옮기기 전 배정 정보. 합치면 배정이 USER로 덮어쓰이므로,
 * 되돌릴 때 AI 배정 · 신뢰도까지 원래대로 살리려면 옮기기 전 값을 따로 들고 있어야 한다.
 */
@Embeddable
class MergedPhotoSnapshot(
    @Column(name = "photo_id", nullable = false)
    val photoId: Long,

    @Column(name = "assigned_by_user_id")
    val assignedByUserId: Long?,

    @Enumerated(EnumType.STRING)
    @Column(name = "assigned_source", nullable = false, length = 20)
    val assignedSource: FolderSource,

    @Column(name = "confidence")
    val confidence: Double?,

    @Column(name = "assigned_at", nullable = false)
    val assignedAt: ZonedDateTime,
)
