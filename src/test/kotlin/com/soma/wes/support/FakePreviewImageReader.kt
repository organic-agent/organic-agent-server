package com.soma.wes.support

import com.soma.wes.photo.service.PreviewImageReader

/** S3 없이 미리보기를 읽는 척한다. 키를 그대로 바이트로 돌려주므로 테스트가 어떤 사진이 갔는지 확인할 수 있다. */
class FakePreviewImageReader : PreviewImageReader {

    override fun readJpeg(previewKey: String, longEdge: Int): ByteArray = previewKey.toByteArray()
}
