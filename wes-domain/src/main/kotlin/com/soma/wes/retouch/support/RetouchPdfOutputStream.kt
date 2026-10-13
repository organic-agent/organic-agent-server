package com.soma.wes.retouch.support

import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import java.io.ByteArrayOutputStream

/** 초과한 PDF를 먼저 메모리에 만들지 않고 저장 중에 중단한다. */
internal class RetouchPdfOutputStream(private val limit: Int) : ByteArrayOutputStream() {
    override fun write(value: Int) {
        validateCapacity(1)
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        validateCapacity(length)
        super.write(bytes, offset, length)
    }

    private fun validateCapacity(length: Int) {
        if (count.toLong() + length > limit) throw RetouchException(RetouchErrorCode.PDF_LIMIT_EXCEEDED)
    }
}
