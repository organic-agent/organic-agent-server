package com.soma.wes.trash.service

import com.soma.wes.trash.config.TrashProperties
import com.soma.wes.trash.repository.ProductChildTrashRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 제품 사용자의 삭제 의사를 즉시 반영하면서 관리자 복원/영구 삭제 계약을 지킨다.
 *
 * 반드시 호출 서비스의 기존 트랜잭션 안에서 실행한다. 자식 행·부모 version·휴지통
 * 메타데이터 중 하나라도 실패하면 전체가 롤백된다.
 */
@Service
class ProductChildTrashService(
    private val repository: ProductChildTrashRepository,
    private val trashProperties: TrashProperties,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteGuestComment(sessionId: Long, commentId: Long, guestId: Long): Boolean =
        deleteCollabComment(
            sessionId = sessionId,
            commentId = commentId,
            authorGuestId = guestId,
            actorLabel = ACTOR_GUEST,
            reasonCode = REASON_GUEST_COMMENT_DELETE,
        )

    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteUserComment(sessionId: Long, commentId: Long): Boolean =
        deleteCollabComment(
            sessionId = sessionId,
            commentId = commentId,
            authorGuestId = null,
            actorLabel = ACTOR_USER,
            reasonCode = REASON_USER_COMMENT_DELETE,
        )

    @Transactional(propagation = Propagation.MANDATORY)
    fun cancelGuestLike(sessionId: Long, collabPhotoId: Long, guestId: Long): Boolean {
        if (!repository.lockActiveCollaboration(sessionId)) return false
        if (!repository.lockCollabPhoto(sessionId, collabPhotoId)) return false

        val deletedAt = ZonedDateTime.now(clock)
        val likeId = repository.softDeleteLike(sessionId, collabPhotoId, guestId, deletedAt) ?: return false
        completeCollaborationDelete(
            sessionId = sessionId,
            resourceType = RESOURCE_LIKE,
            resourceId = likeId,
            actorLabel = ACTOR_GUEST,
            reasonCode = REASON_GUEST_LIKE_CANCEL,
            deletedAt = deletedAt,
        )
        return true
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun removeUserRetouchItem(roundId: Long, photoId: Long): Boolean {
        if (!repository.lockActiveRetouchRound(roundId)) return false

        val deletedAt = ZonedDateTime.now(clock)
        val itemId = repository.softDeleteRetouchItem(roundId, photoId, deletedAt) ?: return false
        check(repository.bumpRetouchRoundVersion(roundId, deletedAt) == 1) {
            "보정 휴지통 이동 중 부모 회차 version 갱신에 실패했습니다."
        }
        repository.createRecord(
            resourceType = RESOURCE_RETOUCH_ITEM,
            resourceId = itemId,
            parentType = PARENT_RETOUCH_REQUEST,
            parentId = roundId,
            actorLabel = ACTOR_USER,
            reasonCode = REASON_USER_RETOUCH_REMOVE,
            deletedAt = deletedAt,
            restoreUntil = deletedAt.plus(trashProperties.retention),
        )
        return true
    }

    private fun deleteCollabComment(
        sessionId: Long,
        commentId: Long,
        authorGuestId: Long?,
        actorLabel: String,
        reasonCode: String,
    ): Boolean {
        if (!repository.lockActiveCollaboration(sessionId)) return false

        val deletedAt = ZonedDateTime.now(clock)
        val deletedId = repository.softDeleteComment(sessionId, commentId, authorGuestId, deletedAt) ?: return false
        completeCollaborationDelete(
            sessionId = sessionId,
            resourceType = RESOURCE_COMMENT,
            resourceId = deletedId,
            actorLabel = actorLabel,
            reasonCode = reasonCode,
            deletedAt = deletedAt,
        )
        return true
    }

    private fun completeCollaborationDelete(
        sessionId: Long,
        resourceType: String,
        resourceId: Long,
        actorLabel: String,
        reasonCode: String,
        deletedAt: ZonedDateTime,
    ) {
        check(repository.bumpCollaborationVersion(sessionId, deletedAt) == 1) {
            "협업 휴지통 이동 중 부모 세션 version 갱신에 실패했습니다."
        }
        repository.createRecord(
            resourceType = resourceType,
            resourceId = resourceId,
            parentType = PARENT_COLLABORATION,
            parentId = sessionId,
            actorLabel = actorLabel,
            reasonCode = reasonCode,
            deletedAt = deletedAt,
            restoreUntil = deletedAt.plus(trashProperties.retention),
        )
    }

    private companion object {
        const val RESOURCE_COMMENT = "COLLAB_COMMENT"
        const val RESOURCE_LIKE = "COLLAB_LIKE"
        const val RESOURCE_RETOUCH_ITEM = "RETOUCH_ITEM"
        const val PARENT_COLLABORATION = "COLLABORATION"
        const val PARENT_RETOUCH_REQUEST = "RETOUCH_REQUEST"

        // 제품 행위자는 admin_accounts FK 대상이 아니므로 actor_admin_id는 NULL이다.
        // 토큰·닉네임·사용자 이름·본문 대신 불변의 비식별 코드만 남긴다.
        const val ACTOR_GUEST = "PRODUCT_GUEST"
        const val ACTOR_USER = "PRODUCT_USER"
        const val REASON_GUEST_COMMENT_DELETE = "PRODUCT_GUEST_SELF_DELETE"
        const val REASON_USER_COMMENT_DELETE = "PRODUCT_USER_COMMENT_MODERATION"
        const val REASON_GUEST_LIKE_CANCEL = "PRODUCT_GUEST_LIKE_CANCEL"
        const val REASON_USER_RETOUCH_REMOVE = "PRODUCT_USER_RETOUCH_REMOVE"
    }
}
