package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.StudioDeletionClaim
import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StudioDeletionClaimRepository : JpaRepository<StudioDeletionClaim, UUID> {

    /**
     * 같은 request_id가 동시에 들어오면 PostgreSQL unique 제약이 승자 하나만 정한다.
     * 예외를 catch하는 대신 update count로 승패를 돌려 트랜잭션을 rollback-only로 만들지 않는다.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO studio_deletion_claims (
                request_id, claim_token, studio_id, operator_user_id, studio_gallery_url, reason
            ) VALUES (
                :requestId, :claimToken, :studioId, :operatorUserId, :studioGalleryUrl, :reason
            )
            ON CONFLICT (request_id) DO NOTHING
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
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
            SELECT claim
            FROM StudioDeletionClaim claim
            WHERE claim.requestId = :requestId AND claim.claimToken = :claimToken
        """,
    )
    fun findOwnedForUpdate(
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
