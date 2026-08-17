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

    /** 결과까지 올라와 끝난 회차의 항목들. 보정본을 선택 앨범에 담는 흐름의 배경이다. */
    fun 결과와_함께_완료된_회차(galleryId: Long, roundNo: Int = 1, photoIds: List<Long>): List<RetouchPhoto> {
        val round = 제출된_회차(galleryId, roundNo, photoIds)
        val items = retouchPhotoRepository.findAllByRoundId(round.requiredId)
        items.forEach {
            it.writeResult(
                resultKey = "galleries/$galleryId/retouch/results/$roundNo/${it.photoId}.jpg",
                resultContentType = "image/jpeg",
            )
        }
        retouchPhotoRepository.saveAllAndFlush(items)

        round.complete(ZonedDateTime.now())
        retouchRoundRepository.saveAndFlush(round)
        return items
    }

    /** 부부가 제출했지만 아직 아무 결과도 없는 회차의 항목들. */
    fun 결과_없는_제출된_회차(galleryId: Long, roundNo: Int = 1, photoIds: List<Long>): List<RetouchPhoto> {
        val round = 제출된_회차(galleryId, roundNo, photoIds)
        return retouchPhotoRepository.findAllByRoundId(round.requiredId)
    }

    /** 항목들에 주석 key를 채운다. 삭제 경로가 주석 파일까지 걷는지 볼 때 쓴다. */
    fun 주석_추가(items: List<RetouchPhoto>): List<RetouchPhoto> {
        items.forEach {
            it.writeRequest(
                requestText = null,
                annotationKey = "galleries/${it.galleryId}/retouch/annotations/${it.photoId}.png",
            )
        }
        return retouchPhotoRepository.saveAllAndFlush(items)
    }

    private fun 담기(round: RetouchRound, photoIds: List<Long>) {
        retouchPhotoRepository.saveAll(
            photoIds.map {
                RetouchPhoto(roundId = round.requiredId, galleryId = round.galleryId, photoId = it)
            },
        )
    }
}
