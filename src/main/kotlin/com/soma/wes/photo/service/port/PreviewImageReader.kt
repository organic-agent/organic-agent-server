package com.soma.wes.photo.service.port

/**
 * 스토리지의 미리보기를 LLM에 보낼 크기의 JPEG로 읽는다.
 *
 * 클라이언트의 업로드·다운로드는 서버를 지나지 않지만, AI 호출 재료로 미리보기 몇 장을 읽는 것은
 * 그 원칙 밖이다(CLAUDE.md). 원본이 아니라 `previews/` 파생물만 읽는다.
 */
interface PreviewImageReader {

    /** [longEdge]로 줄인 JPEG 바이트. 없거나 읽지 못하면 `PhotoException(STORAGE_READ_FAILED)`. */
    fun readJpeg(previewKey: String, longEdge: Int): ByteArray

    /**
     * 줄이지 않은 미리보기 바이트.
     *
     * 보정 요청의 탭 지점처럼 **픽셀 좌표를 직접 다뤄야 할 때** 쓴다 — 먼저 줄여 버리면 크롭 한 변이
     * 그만큼 거칠어진다. 화면에 그대로 보내는 용도가 아니므로 호출자가 쓰고 버린다.
     */
    fun read(previewKey: String): ByteArray
}
