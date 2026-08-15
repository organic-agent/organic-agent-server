package com.soma.wes.studio.service

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

/**
 * 사전 조회(`existsBy*`)를 통과한 뒤 DB 유니크 제약에 걸리는 경로를 다룬다.
 *
 * 서비스는 이 위반을 도메인 예외로 바꾸지 않는다. 사전 조회가 대부분을 걸러내고, 남는 것은
 * 두 요청이 같은 순간에 들어온 경우뿐이라 그대로 전파시킨다.
 */
class StudioServiceConstraintTest {

    @Test
    fun `동시 생성이 사전 조회를 함께 통과하면 갤러리 주소 경쟁은 DB 제약이 막는다`() {
        // given
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
        // 둘 다 "쓸 수 있다"를 보고 지나가게 만든다. 사전 조회는 TOCTOU라 이것이 실제로 가능하다.
        whenever(studioRepository.existsByGalleryUrl("race-url")).thenAnswer {
            precheckBarrier.await(5, TimeUnit.SECONDS)
            false
        }
        whenever(studioRepository.save(any<Studio>())).thenAnswer {
            if (wonInsert.compareAndSet(false, true)) {
                persisted
            } else {
                throw duplicate("uk_studios_gallery_url")
            }
        }

        val executor = Executors.newFixedThreadPool(2)
        try {
            // when
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

            // then
            // 한쪽만 실패한다. 중복 행이 생기지는 않는다는 것이 이 테스트의 요지다.
            assertThat(results.filterNotNull().single())
                .isInstanceOf(DataIntegrityViolationException::class.java)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `어떤 무결성 위반이든 도메인 예외로 바꾸지 않고 그대로 전파한다`() {
        // when & then
        listOf("uk_studios_gallery_url", "uk_studios_user_id", "uk_unknown").forEach { constraintName ->
            val failure = duplicate(constraintName)

            assertThatThrownBy {
                serviceFailingWith(failure).create(1L, CreateStudioRequest("스튜디오", "studio-url", null))
            }.describedAs("제약 $constraintName 이 그대로 올라와야 한다")
                .isInstanceOf(DataIntegrityViolationException::class.java)
                .isSameAs(failure)
        }
    }

    private fun serviceFailingWith(failure: DataIntegrityViolationException): StudioService {
        val studioRepository = mock<StudioRepository>()
        val userRepository = mock<UserRepository>()

        whenever(userRepository.findById(1L)).thenReturn(Optional.of(newUser(1L)))
        whenever(studioRepository.existsByUserId(1L)).thenReturn(false)
        whenever(studioRepository.existsByGalleryUrl("studio-url")).thenReturn(false)
        whenever(studioRepository.save(any<Studio>())).thenThrow(failure)

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
