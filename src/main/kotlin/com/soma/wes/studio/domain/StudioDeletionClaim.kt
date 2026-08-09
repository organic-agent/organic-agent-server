package com.soma.wes.studio.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID

/**
 * S3 삭제를 시작하기 전에 같은 요청 ID의 동시 실행을 한 요청으로 제한한다.
 *
 * 완료된 요청은 [StudioDeletionAudit]가 영구 보존한다. S3 삭제가 시작된 뒤 실패하면 이 행을
 * 지우지 않고 [StudioDeletionClaimState.RETRYABLE]로 바꿔 writer를 계속 막는다.
 * 같은 요청만 token을 교체해
 * 멱등하게 S3 삭제를 재개할 수 있다.
 */
@Entity
@Table(
    name = "studio_deletion_claims",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_studio_deletion_claims_studio_id", columnNames = ["studio_id"]),
    ],
)
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

    @Column(name = "plan_version", nullable = false, updatable = false)
    val planVersion: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val state: StudioDeletionClaimState = StudioDeletionClaimState.RUNNING,
)

enum class StudioDeletionClaimState {
    RUNNING,
    RETRYABLE,
}
