package com.soma.wes.selection.domain

/**
 * 선택 앨범이 아직 고르는 중인지, 작가에게 넘어갔는지.
 */
enum class PhotoSelectionStatus {

    /** 부부가 담고 빼는 중. 갤러리가 열려 있고 마감 전이라면 언제든 바뀐다. */
    SELECTING,

    /** 부부가 제출했다. 작가가 이 목록을 보고 보정에 들어가므로 되돌리는 것도 작가만 한다. */
    SUBMITTED,
}
