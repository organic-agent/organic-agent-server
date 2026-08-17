package com.soma.wes.retouch.fixture

import com.soma.wes.retouch.domain.RetouchPhoto
import com.soma.wes.retouch.domain.RetouchRound
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import com.soma.wes.retouch.repository.RetouchRoundRepository
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

/**
 * 과거 회차를 배경으로 만든다. Phase 1에는 회차를 REQUESTED/COMPLETED로 보내는 작가 API가
 * 없어, "이전 회차가 있는 갤러리"는 행을 직접 만들어야 한다.
 */
@Component
class RetouchFixture(
    private val retouchRoundRepository: RetouchRoundRepository,
    private val retouchPhotoRepository: RetouchPhotoRepository,
) {

    /** 부부가 제출하고 작가 응답을 기다리는 회차. */
    fun 제출된_회차(galleryId: Long, roundNo: Int = 1, photoIds: List<Long> = emptyList()): RetouchRound {
        val round = retouchRoundRepository.save(RetouchRound(galleryId = galleryId, roundNo = roundNo))
        담기(round, photoIds)
        round.submit(ZonedDateTime.now())
        return retouchRoundRepository.saveAndFlush(round)
    }

    /** 작가가 끝낸 회차. 다음 회차를 시작할 수 있는 상태다. */
    fun 완료된_회차(galleryId: Long, roundNo: Int = 1, photoIds: List<Long> = emptyList()): RetouchRound {
        val round = 제출된_회차(galleryId, roundNo, photoIds)
        round.complete(ZonedDateTime.now())
        return retouchRoundRepository.saveAndFlush(round)
    }

    private fun 담기(round: RetouchRound, photoIds: List<Long>) {
        retouchPhotoRepository.saveAll(
            photoIds.map {
                RetouchPhoto(roundId = round.requiredId, galleryId = round.galleryId, photoId = it)
            },
        )
    }
}
