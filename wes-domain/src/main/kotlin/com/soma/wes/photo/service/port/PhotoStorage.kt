package com.soma.wes.photo.service.port

import com.soma.wes.photo.dto.PresignedUploadDto


interface PhotoStorage {

    /**
     * 갤러리 하나의 키 공간. 원본·보정 주석·보정 결과가 전부 이 아래에 있다 — 키를 조립하는
     * 곳은 이 메서드를 지나야 한다. 환경은 버킷으로 갈리므로 키에 환경 구분자는 없다.
     */
    fun galleryPrefix(galleryId: Long): String

    /** 사진 한 장이 저장될 위치를 정한다.*/
    fun buildKey(galleryId: Long, originalFileName: String): String

    /**
     * 프론트가 직접 PUT 할 URL.
     * [contentType]이 서명에 포함되므로 업로드할 때 같은 값을 보내야 서명이 맞는다. [contentLength]를 주면 그 바이트 수도
     * 서명에 들어가 다른 크기의 객체는 S3가 거절한다 — 서버가 객체를 받지 않고도 크기 상한을 지키는 유일한 길이다(presigned PUT에는
     * 범위 조건이 없다). [crc32c]를 주면 그 값이 `x-amz-checksum-crc32c` 헤더로 서명에 들어가 브라우저가 같은 헤더를 보내야 하고,
     * S3가 받은 바이트의 CRC32C를 그 값과 대조한다. 서버는 바디를 만지지 않으므로 값은 클라이언트가 계산해 온다 — 값 없이
     * 알고리즘만 지정하면 SDK가 자기 내부 헤더를 서명에 넣어 어떤 브라우저도 맞출 수 없다.
     * 원본 사진 업로드는 둘 다 항상 주고, 보정 주석·관리자 교체처럼 크기를 미리 알 수 없는 업로드는 둘 다 비운다.
     */
    fun presignUpload(
        key: String,
        contentType: String,
        contentLength: Long? = null,
        crc32c: String? = null,
    ): PresignedUploadDto

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

    /** 최고 관리자 원본 다운로드. 구현이 별도 disposition을 지원하지 않으면 원본 조회 URL을 쓴다. */
    fun presignDownload(key: String, originalFileName: String): String = presignOriginal(key)

    /** 브라우저가 PUT을 완료했다고 알린 키가 실제 버킷에 존재하는지 확인한다. */
    fun exists(key: String): Boolean

    /** 휴지통 비우기에서 원본과 미리보기를 물리 삭제한다. 없는 키를 다시 지워도 성공해야 한다. */
    fun deleteAll(keys: Collection<String>)

    /**
     * 같은 버킷 안에서 객체 하나를 복제한다. Mock 갤러리가 템플릿 사진을 자기 키 공간으로
     * 가져올 때 쓴다. 스토리지 내부 복사라 바이트는 서버를 지나지 않는다.
     */
    fun copy(sourceKey: String, targetKey: String)
}
