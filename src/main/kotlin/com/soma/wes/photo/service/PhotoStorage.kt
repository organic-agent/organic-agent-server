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

    /**
     * 원본을 원래 크기로 여는 URL. [presignView]와 서명하는 방식은 같고 수명만 다르다.
     *
     * 상세 화면은 목록과 달리 오래 열어두는 화면이라, 목록용 수명(15분)으로 서명하면
     * 확대해 보는 도중에 만료된다. 그 순간 이미지가 사라지는 것이 아니라 S3가 403을
     * 돌려주므로, 사용자에게는 사진이 깨진 것처럼 보인다.
     */
    fun presignOriginal(key: String): String
}
