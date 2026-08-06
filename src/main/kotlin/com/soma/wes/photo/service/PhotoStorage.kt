package com.soma.wes.photo.service


interface PhotoStorage {

    /** 사진 한 장이 저장될 위치를 정한다.*/
    fun buildKey(galleryId: Long, originalFileName: String): String

    /**
     * 프론트가 직접 PUT 할 URL.
     * [contentType]이 서명에 포함되므로 업로드할 때 같은 값을 보내야 서명이 맞는다.
     */
    fun presignUpload(key: String, contentType: String): String

    /** 비공개 버킷의 사진을 브라우저가 그릴 수 있게 하는 유일한 통로. */
    fun presignView(key: String): String
}
