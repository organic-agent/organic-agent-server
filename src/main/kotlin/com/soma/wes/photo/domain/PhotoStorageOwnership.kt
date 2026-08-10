package com.soma.wes.photo.domain

/**
 * 사진이 가리키는 오브젝트를 누가 소유하는지 나타낸다.
 *
 * [GALLERY]는 갤러리와 수명이 같아 스튜디오 hard delete 때 S3에서도 지운다.
 * [SHARED_TEMPLATE]은 여러 Mock 갤러리가 같은 불변 객체를 참조하므로 DB 참조만 지우고
 * 원본과 미리보기 객체는 보존한다.
 */
enum class PhotoStorageOwnership {
    GALLERY,
    SHARED_TEMPLATE,
}
