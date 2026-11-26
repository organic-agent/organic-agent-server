package com.soma.wes.gallery.support

import com.soma.wes.analysis.support.ConceptAssignmentLoader
import com.soma.wes.folder.support.AiFolderMaterializer
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryProgressResetRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.repository.PhotoSelectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 갤러리를 "사진을 올리고 AI 분석이 끝난 직후"로 되돌린다. 심사용 갤러리 초기화와 관리자 초기화가 함께 쓴다.
 *
 * 지우는 것: 셀렉·별점·메모·댓글·거르기·추천·보정 요청·앨범·협업 링크·폴더 수정·활동 기록. 남기는 것: 사진·AI 분석·이용권·갤러리 설정.
 * 폴더는 지운 뒤 최신 AI 분석으로 다시 만든다 — 파이프라인이 폴더를 만들 때와 같은 [AiFolderMaterializer]다.
 * 분석이 아직 없거나 사진이 모두 휴지통에 있으면 폴더 없이 둔다.
 *
 * 인가는 호출자가 먼저 한다. 되돌릴 수 없으니 호출자를 좁게 둔다(심사용 계정 본인, 관리자).
 */
@Service
class GalleryProgressResetter(
    private val galleryRepository: GalleryRepository,
    private val resetRepository: GalleryProgressResetRepository,
    private val selectionRepository: PhotoSelectionRepository,
    private val conceptAssignmentLoader: ConceptAssignmentLoader,
    private val aiFolderMaterializer: AiFolderMaterializer,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val clock: Clock,
) {

    /**
     * [withMembership]이면 멤버와 초대도 지운다 — 관리자가 시연 갤러리를 아무도 들어오지 않은 상태로 되돌릴 때다.
     * 심사용 초기화는 심사위원이 함께 쓰는 파트너를 남겨야 해서 끈다.
     *
     * 갤러리 행을 잠근 뒤 지워서, 초기화 도중 들어온 셀렉·별점이 지운 뒤에 남지 않게 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun reset(galleryId: Long, withMembership: Boolean): Gallery {
        val gallery = galleryRepository.requireWithLockById(galleryId)

        resetRepository.deleteProgress(galleryId, ZonedDateTime.now(clock))
        if (withMembership) {
            resetRepository.deleteMembership(galleryId)
        }
        selectionRepository.save(PhotoSelection(galleryId = galleryId))
        gallery.resetProgress()

        if (hasPhotosToOrganize(galleryId)) {
            aiFolderMaterializer.materialize(galleryId)
        }
        return gallery
    }

    /** 분석이 끝났고 휴지통 밖에 분류된 사진이 있을 때만 폴더를 만든다. 없으면 물질화가 거절해 초기화 전체가 롤백된다. */
    private fun hasPhotosToOrganize(galleryId: Long): Boolean =
        conceptAssignmentLoader.loadLatest(galleryId) != null && photoAnalysisRepository.existsGroupingByGalleryId(galleryId)
}
