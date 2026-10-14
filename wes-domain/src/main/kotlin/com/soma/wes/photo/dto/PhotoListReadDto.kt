package com.soma.wes.photo.dto

import com.soma.wes.photo.domain.Photo
import org.springframework.data.domain.Page

/**
 * 사진 목록 한 페이지를 만드는 데 DB에서 읽은 것 전부. `PhotoService.list`가 읽기 트랜잭션 안에서 채우고, 보기 주소 서명은
 * 연결을 놓은 뒤 이것으로 한다(#286 (b)).
 */
data class PhotoListReadDto(
    val page: Page<Photo>,
    val viewData: PhotoViewDataDto,
    /** 작가(스튜디오 관리자)에게는 부부의 별점·메모를 숨긴다. */
    val studioViewer: Boolean,
)
