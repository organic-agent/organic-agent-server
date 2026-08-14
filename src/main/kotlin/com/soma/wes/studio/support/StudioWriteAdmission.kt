package com.soma.wes.studio.support

import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioDeletionClaimRepository
import com.soma.wes.studio.repository.StudioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * hard delete 스냅샷을 바꿀 수 있는 짧은 DB writer의 공통 입장 관문.
 *
 * 호출자는 저장까지 포함하는 트랜잭션 안에서 이 메서드를 먼저 호출해야 한다. shared advisory
 * fence를 잡은 뒤 별도 쿼리로 claim을 확인한다. writer가 먼저 입장했다면 삭제 준비는
 * 부모 FK lock에서 writer commit을 기다려 새 행을 snapshot에 포함하거나, row lock으로 흡수할 수
 * 없는 writer의 shared fence를 보고 S3 호출 전에 거절된다. 삭제가 먼저 exclusive fence와 claim을
 * 확정했다면 writer가 저장 전에 거절된다. `MANDATORY`는 fence만 얻고 트랜잭션을 끝낸 뒤
 * 저장하는 잘못된 호출을 막는다.
 */
@Service
class StudioWriteAdmission(
    private val studioRepository: StudioRepository,
    private val claimRepository: StudioDeletionClaimRepository,
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireWritableByUserId(userId: Long): Studio {
        val studio = studioRepository.findByUserId(userId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        val studioId = checkNotNull(studio.id) { "저장되지 않은 스튜디오입니다." }
        admit(studioId)
        if (!studioRepository.existsById(studioId)) {
            throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        }
        return studio
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireWritable(studioId: Long) {
        admit(studioId)
        if (!studioRepository.existsById(studioId)) {
            throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        }
    }

    private fun admit(studioId: Long) {
        if (!studioRepository.tryAcquireSharedWriteFence(StudioWriteFence.key(studioId))) {
            throw StudioException(StudioErrorCode.STUDIO_DELETION_IN_PROGRESS)
        }
        requireNoActiveDeletion(studioId)
    }

    private fun requireNoActiveDeletion(studioId: Long) {
        if (claimRepository.findByStudioId(studioId) != null) {
            throw StudioException(StudioErrorCode.STUDIO_DELETION_IN_PROGRESS)
        }
    }
}
