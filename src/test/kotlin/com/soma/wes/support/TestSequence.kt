package com.soma.wes.support

import java.util.concurrent.atomic.AtomicLong

/**
 * 유니크 제약(email, providerId, galleryUrl, storage key…)을 피해야 하는 자리에 쓸 전역 시퀀스.
 * 파일마다 `AtomicLong`을 복제하지 않기 위한 한 곳이다.
 */
object TestSequence {

    private val sequence = AtomicLong(System.nanoTime())

    fun next(): Long = sequence.incrementAndGet()
}
