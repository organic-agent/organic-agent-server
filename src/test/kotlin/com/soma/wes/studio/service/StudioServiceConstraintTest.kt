package com.soma.wes.studio.service

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.util.Optional
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class StudioServiceConstraintTest {

    @Test
    fun `동시 생성이 사전 조회를 함께 통과해도 갤러리 주소 경쟁은 표준 409로 끝난다`() {
        val studioRepository = mock<StudioRepository>()
        val userRepository = mock<UserRepository>()
        val service = StudioService(studioRepository, userRepository)
        val precheckBarrier = CyclicBarrier(2)
        val persisted = persistedStudio()
        val wonInsert = AtomicBoolean(false)

        whenever(userRepository.findById(any())).thenAnswer { invocation ->
            Optional.of(newUser(invocation.getArgument(0)))
        }
        whenever(studioRepository.existsByUserId(any())).thenReturn(false)
        whenever(studioRepository.existsByGalleryUrl("race-url")).thenAnswer {
            precheckBarrier.await(5, TimeUnit.SECONDS)
            false
        }
        whenever(studioRepository.saveAndFlush(any())).thenAnswer {
            if (wonInsert.compareAndSet(false, true)) {
                persisted
            } else {
                throw duplicate("uk_studios_gallery_url")
            }
        }

        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(1L, 2L).map { userId ->
                executor.submit<Throwable?> {
                    try {
                        service.create(userId, CreateStudioRequest("스튜디오", "  RACE-URL  ", null))
                        null
                    } catch (throwable: Throwable) {
                        throwable
                    }
                }
            }.map { it.get(10, TimeUnit.SECONDS) }

            val failure = results.filterNotNull().single()
            assertIs<StudioException>(failure)
            assertEquals(StudioErrorCode.GALLERY_URL_DUPLICATED, failure.errorCode)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `동시 온보딩의 사용자 유니크 경쟁은 이미 생성된 스튜디오 409로 변환한다`() {
        val service = serviceFailingWith("uk_studios_user_id")

        val exception = kotlin.test.assertFailsWith<StudioException> {
            service.create(1L, CreateStudioRequest("스튜디오", "studio-url", null))
        }

        assertEquals(StudioErrorCode.STUDIO_ALREADY_EXISTS, exception.errorCode)
    }

    @Test
    fun `알 수 없는 무결성 위반은 숨기지 않는다`() {
        val failure = duplicate("uk_unknown")
        val service = serviceFailingWith(failure)

        val thrown = kotlin.test.assertFailsWith<DataIntegrityViolationException> {
            service.create(1L, CreateStudioRequest("스튜디오", "studio-url", null))
        }

        assertSame(failure, thrown)
    }

    private fun serviceFailingWith(constraintName: String): StudioService = serviceFailingWith(duplicate(constraintName))

    private fun serviceFailingWith(failure: DataIntegrityViolationException): StudioService {
        val studioRepository = mock<StudioRepository>()
        val userRepository = mock<UserRepository>()

        whenever(userRepository.findById(1L)).thenReturn(Optional.of(newUser(1L)))
        whenever(studioRepository.existsByUserId(1L)).thenReturn(false)
        whenever(studioRepository.existsByGalleryUrl("studio-url")).thenReturn(false)
        whenever(studioRepository.saveAndFlush(any())).thenThrow(failure)

        return StudioService(studioRepository, userRepository)
    }

    private fun persistedStudio(): Studio = mock<Studio>().also { studio ->
        whenever(studio.id).thenReturn(1L)
        whenever(studio.name).thenReturn("스튜디오")
        whenever(studio.galleryUrl).thenReturn("race-url")
    }

    private fun newUser(userId: Long) = User(
        provider = OAuthProvider.KAKAO,
        providerId = "studio-constraint-$userId",
        nickname = "테스터",
    )

    private fun duplicate(constraintName: String) = DataIntegrityViolationException(
        "duplicate",
        ConstraintViolationException("duplicate", SQLException("duplicate"), constraintName),
    )
}
