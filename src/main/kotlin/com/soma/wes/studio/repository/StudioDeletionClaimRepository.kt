package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.StudioDeletionClaim
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StudioDeletionClaimRepository : JpaRepository<StudioDeletionClaim, UUID> {

    fun findByStudioId(studioId: Long): StudioDeletionClaim?

    /**
     * 같은 request_id가 동시에 들어오면 PostgreSQL unique 제약이 승자 하나만 정한다.
     * 예외를 catch하는 대신 update count로 승패를 돌려 트랜잭션을 rollback-only로 만들지 않는다.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO studio_deletion_claims (
                request_id, claim_token, studio_id, operator_user_id, studio_gallery_url, reason, plan_version
            ) VALUES (
                :requestId, :claimToken, :studioId, :operatorUserId, :studioGalleryUrl, :reason, :planVersion
            )
            ON CONFLICT DO NOTHING
        """,
        nativeQuery = true,
    )
    fun tryClaim(
        @Param("requestId") requestId: UUID,
        @Param("claimToken") claimToken: UUID,
        @Param("studioId") studioId: Long,
        @Param("operatorUserId") operatorUserId: Long,
        @Param("studioGalleryUrl") studioGalleryUrl: String,
        @Param("reason") reason: String,
        @Param("planVersion") planVersion: Int,
    ): Int

    /** S3 삭제가 시작된 실패 요청을 writer를 막은 채 같은 요청으로만 재개할 수 있게 한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE studio_deletion_claims
            SET state = 'RETRYABLE',
                state_changed_at = statement_timestamp()
            WHERE request_id = :requestId
              AND claim_token = :claimToken
              AND plan_version = :planVersion
              AND state = 'RUNNING'
        """,
        nativeQuery = true,
    )
    fun markRetryable(
        @Param("requestId") requestId: UUID,
        @Param("claimToken") claimToken: UUID,
        @Param("planVersion") planVersion: Int,
    ): Int

    /** retryable claim의 소유 token을 교체해 동시 재개를 하나로 제한한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE studio_deletion_claims
            SET claim_token = :newClaimToken,
                state = 'RUNNING',
                claimed_at = statement_timestamp(),
                state_changed_at = statement_timestamp()
            WHERE request_id = :requestId
              AND claim_token = :previousClaimToken
              AND plan_version = :planVersion
              AND state = 'RETRYABLE'
        """,
        nativeQuery = true,
    )
    fun tryResume(
        @Param("requestId") requestId: UUID,
        @Param("previousClaimToken") previousClaimToken: UUID,
        @Param("newClaimToken") newClaimToken: UUID,
        @Param("planVersion") planVersion: Int,
    ): Int

    @Query(
        value = """
            SELECT *
            FROM studio_deletion_claims
            WHERE request_id = :requestId
              AND claim_token = :claimToken
              AND state = 'RUNNING'
            FOR UPDATE
        """,
        nativeQuery = true,
    )
    fun findOwnedRunningForUpdate(
        @Param("requestId") requestId: UUID,
        @Param("claimToken") claimToken: UUID,
    ): StudioDeletionClaim?

    @Modifying
    @Query(
        """
            DELETE FROM StudioDeletionClaim claim
            WHERE claim.requestId = :requestId AND claim.claimToken = :claimToken
        """,
    )
    fun release(
        @Param("requestId") requestId: UUID,
        @Param("claimToken") claimToken: UUID,
    ): Int
}
