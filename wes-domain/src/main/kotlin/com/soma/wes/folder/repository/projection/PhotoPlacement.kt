package com.soma.wes.folder.repository.projection

/** 사진 한 장이 지금 든 세부 폴더. AI 폴더 계획이 갤러리 전체의 배정을 엔티티 없이 읽으려고 뗀 것이다. */
interface PhotoPlacement {
    val photoId: Long
    val detailFolderId: Long
}
