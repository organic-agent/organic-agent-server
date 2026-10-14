package com.soma.wes.retouch.dto

/**
 * 정제 호출에 실을 사진. [crop]은 탭 지점이 있을 때만 있다(사진 전체 메모면 null).
 *
 * `ByteArray`를 들어 `data class`로 두지 않는다 — equals/hashCode가 참조 비교라 값 의미가 어긋난다.
 */
class RetouchPointImagesDto(val full: ByteArray, val crop: ByteArray?)
