package com.soma.wes.global.logging

import org.slf4j.MDC
import java.util.UUID

/**
 * 로그 줄에 따라붙는 키(MDC). 여기 실은 값은 그 스레드가 찍는 **모든** 줄에 붙는다 — 어댑터·저장소가 남긴 줄도
 * 갤러리·잡으로 묶여 "이 갤러리에 무슨 일이 있었나"를 한 번에 모을 수 있다.
 *
 * 키 이름은 `logback-spring.xml`의 `%X{...}`와 같아야 한다. 요청 스레드는 `HttpLoggingFilter`가 요청 끝에 전부 비우고,
 * 스케줄러 스레드는 [sweep]이 회차 끝에 비운다 — 풀 스레드는 재사용되므로 비우지 않으면 앞 갤러리의 값이 다음 줄에 남는다.
 */
object LogContext {

    const val TRACE_ID = "traceId"
    const val GALLERY_ID = "galleryId"
    const val JOB_ID = "jobId"
    const val UPLOAD_SESSION_ID = "uploadSessionId"
    const val USER_ID = "userId"

    /** 스케줄러 회차의 traceId 머리말. 요청의 traceId(16자리 hex)와 섞이지 않게 한다. */
    const val SWEEP_TRACE_PREFIX = "sweep-"

    /** 머리말 뒤에 붙는 무작위 hex 길이. 한 서버의 회차를 구분하기에 충분하다. */
    const val SWEEP_TRACE_LENGTH = 12

    /**
     * 스케줄러 한 회차. traceId를 새로 만들어 그 회차가 남긴 줄을 한 번에 모을 수 있게 하고, 끝나면 이 스레드의 MDC를 전부 비운다.
     * 요청 스레드에서 부르지 않는다 — 요청의 traceId까지 지운다.
     */
    inline fun <T> sweep(block: () -> T): T {
        val traceId = UUID.randomUUID().toString().replace("-", "").take(SWEEP_TRACE_LENGTH)
        MDC.put(TRACE_ID, "$SWEEP_TRACE_PREFIX$traceId")
        try {
            return block()
        } finally {
            MDC.clear()
        }
    }

    /** [block] 동안 갤러리(와 잡)를 싣고, 끝나면 들어오기 전 값으로 되돌린다. 갤러리를 돌며 일하는 스케줄러 단계가 쓴다. */
    inline fun <T> gallery(galleryId: Long, jobId: Long? = null, block: () -> T): T {
        val previousGalleryId = MDC.get(GALLERY_ID)
        val previousJobId = MDC.get(JOB_ID)
        MDC.put(GALLERY_ID, galleryId.toString())
        if (jobId != null) MDC.put(JOB_ID, jobId.toString())
        try {
            return block()
        } finally {
            restore(GALLERY_ID, previousGalleryId)
            restore(JOB_ID, previousJobId)
        }
    }

    fun restore(key: String, previous: String?) {
        if (previous == null) MDC.remove(key) else MDC.put(key, previous)
    }
}
