package com.soma.wes.retouch.dto


/** 다운로드 모달에서 고르는 사진 범위. 저장된 회차 상태를 바꾸지 않는다. */
enum class RetouchPdfScopeDto(val value: String) { ALL("all"), NO_RESULT("noResult"), MEMO("memo") }
