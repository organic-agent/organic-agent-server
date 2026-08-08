package com.soma.wes.studio.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * S3 삭제를 시작하기 전에 같은 요청 ID의 동시 실행을 한 요청으로 제한한다.
 *
 * 완료된 요청은 [StudioDeletionAudit]가 영구 보존한다. 이 행은 실행 중에만 존재하며 실패하면
 * 지워 같은 요청으로 다시 시도할 수 있다.
 */
@Entity
@Table(name = "studio_deletion_claims")
class StudioDeletionClaim(

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    val requestId: UUID,

    @Column(name = "claim_token", nullable = false, updatable = false)
    val claimToken: UUID,

    @Column(name = "studio_id", nullable = false, updatable = false)
    val studioId: Long,

    @Column(name = "operator_user_id", nullable = false, updatable = false)
    val operatorUserId: Long,

    @Column(name = "studio_gallery_url", nullable = false, updatable = false, length = 255)
    val studioGalleryUrl: String,

    @Column(nullable = false, updatable = false, length = 1000)
    val reason: String,
)
