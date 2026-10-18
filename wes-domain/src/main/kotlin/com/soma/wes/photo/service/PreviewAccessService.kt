package com.soma.wes.photo.service

import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.dto.PreviewAccessDto
import com.soma.wes.photo.service.port.PreviewCdn
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 미리보기 접근 쿠키를 준다(#336 ADR-0001). 웹은 갤러리를 열 때와 [PreviewAccessDto.refreshAfterSeconds]마다 부른다 — 목록을 다시
 * 읽지 않고 쿠키만 늘린다. 연장할 때마다 권한을 다시 보므로, 멤버에서 빠진 사람은 쿠키 수명(20분) 안에 미리보기를 잃는다.
 */
@Service
class PreviewAccessService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val previewCdn: PreviewCdn,
) {

    /**
     * 갤러리를 볼 수 있는 부부·작가에게 그 갤러리 미리보기 경로의 쿠키를 준다(`requireViewer`와 같은 문 — 목록을 볼 수 있으면 사진도 볼 수 있다).
     * 하객은 이 문을 쓰지 않는다(공유된 사진만 보는 권한이라 갤러리 경로 쿠키를 줄 수 없음). CDN이 꺼져 있으면 쿠키 없이 같은 모양을 돌려준다.
     */
    @Transactional(readOnly = true)
    fun issue(galleryId: Long, userId: Long): PreviewAccessDto {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        return previewCdn.accessFor(galleryId)
    }
}
