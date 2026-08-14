package com.soma.wes.photo.service


interface PhotoStorage {

    /** 사진 한 장이 저장될 위치를 정한다.*/
    fun buildKey(galleryId: Long, originalFileName: String): String

    /**
     * 프론트가 직접 PUT 할 URL.
     * [contentType]이 서명에 포함되므로 업로드할 때 같은 값을 보내야 서명이 맞는다.
     */
    fun presignUpload(key: String, contentType: String): PresignedUpload

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

    /** 운영자 승인 삭제에서 원본과 미리보기를 물리 삭제한다. 없는 키를 다시 지워도 성공해야 한다. */
    fun deleteAll(keys: Collection<String>)

    /**
     * 같은 버킷 안에서 객체 하나를 복제한다. Mock 갤러리가 템플릿 사진을 자기 키 공간으로
     * 가져올 때 쓴다. 스토리지 내부 복사라 바이트는 서버를 지나지 않는다.
     */
    fun copy(sourceKey: String, targetKey: String)
}
