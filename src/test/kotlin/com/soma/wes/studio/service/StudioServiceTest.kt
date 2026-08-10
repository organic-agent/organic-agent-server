package com.soma.wes.studio.service

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.dto.request.UpdateStudioRequest
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.studio.support.StudioWriteAdmission
import com.soma.wes.user.domain.User
import com.soma.wes.user.domain.UserType
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DataJpaTest
@Import(TestcontainersConfiguration::class, TimeConfig::class, StudioWriteAdmission::class, StudioService::class)
class StudioServiceTest @Autowired constructor(
    private val studioService: StudioService,
    private val studioRepository: StudioRepository,
    private val userRepository: UserRepository,
) {

    private var sequence = 0

    private fun signUp(): Long {
        sequence++
        return checkNotNull(
            userRepository.save(
                User(
                    provider = OAuthProvider.KAKAO,
                    providerId = "studio-service-$sequence",
                    nickname = "테스터",
                    email = "tester-$sequence@example.com",
                ),
            ).id,
        )
    }

    @Test
    fun `스튜디오를 만들면 사진작가로 확정된다`() {
        // 종류를 고르는 화면이 없다. 스튜디오를 만드는 행동 자체가 "나는 작가다"라는 선언이다.
        val userId = signUp()

        val studio = studioService.create(userId, CreateStudioRequest("오가닉 스튜디오", "  Organic-Studio  ", "인스타그램"))

        assertEquals("organic-studio", studio.galleryUrl)
        assertEquals(userId, studioRepository.findById(studio.id).orElseThrow().userId)
        assertEquals(UserType.PHOTOGRAPHER, userRepository.findById(userId).orElseThrow().userType)
    }

    @Test
    fun `유입 경로는 없어도 된다`() {
        val userId = signUp()

        val studio = studioService.create(userId, CreateStudioRequest("스튜디오", "no-channel", null))

        assertEquals(null, studio.inflowChannel)
    }

    @Test
    fun `한 사람이 스튜디오를 둘 만들 수 없다`() {
        val userId = signUp()
        studioService.create(userId, CreateStudioRequest("첫 번째", "first-studio", null))

        val exception = assertFailsWith<StudioException> {
            studioService.create(userId, CreateStudioRequest("두 번째", "second-studio", null))
        }

        // 이미 종류도 PHOTOGRAPHER라, 검사 순서가 뒤집히면 USER_409_1이 나가 원인을 가린다.
        assertEquals(StudioErrorCode.STUDIO_ALREADY_EXISTS, exception.errorCode)
    }

    @Test
    fun `이미 쓰는 주소로는 만들 수 없다`() {
        studioService.create(signUp(), CreateStudioRequest("먼저 만든 곳", "taken-url", null))

        val exception = assertFailsWith<StudioException> {
            studioService.create(signUp(), CreateStudioRequest("나중에 온 곳", "  TAKEN-URL  ", null))
        }

        assertEquals(StudioErrorCode.GALLERY_URL_DUPLICATED, exception.errorCode)
    }

    @Test
    fun `초대로 들어온 예비 부부는 스튜디오를 만들 수 없다`() {
        // 종류를 바꾸면 이미 수락한 초대의 주인이 어긋난다.
        val userId = signUp()
        userRepository.findById(userId).orElseThrow().selectType(UserType.CLIENT)

        val exception = assertFailsWith<UserException> {
            studioService.create(userId, CreateStudioRequest("스튜디오", "client-studio", null))
        }

        assertEquals(UserErrorCode.USER_TYPE_ALREADY_SELECTED, exception.errorCode)
        assertFalse(studioRepository.existsByUserId(userId))
    }

    @Test
    fun `쓸 수 없는 주소는 거절한다`() {
        val exception = assertFailsWith<StudioException> {
            studioService.create(signUp(), CreateStudioRequest("스튜디오", "API", null))
        }

        assertEquals(StudioErrorCode.INVALID_GALLERY_URL, exception.errorCode)
    }

    @Test
    fun `서비스가 먼저 쓰는 경로는 선점할 수 없다`() {
        // 도메인 바로 아래에 붙는 주소라 선점당하면 그 경로로 갈 수 없게 된다.
        val exception = assertFailsWith<StudioException> {
            studioService.create(signUp(), CreateStudioRequest("스튜디오", "galleries", null))
        }

        assertEquals(StudioErrorCode.INVALID_GALLERY_URL, exception.errorCode)
    }

    @Test
    fun `없는 사용자로는 만들 수 없다`() {
        val exception = assertFailsWith<UserException> {
            studioService.create(99999999L, CreateStudioRequest("스튜디오", "ghost-studio", null))
        }

        assertEquals(UserErrorCode.USER_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun `온보딩을 마치지 않았으면 내 스튜디오가 없다`() {
        // 프론트는 이 404를 보고 스튜디오 생성 화면으로 보낸다.
        val exception = assertFailsWith<StudioException> { studioService.getMyStudio(signUp()) }

        assertEquals(StudioErrorCode.STUDIO_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun `이름과 주소를 바꾼다`() {
        val userId = signUp()
        studioService.create(userId, CreateStudioRequest("옛 이름", "old-url", null))

        val updated = studioService.updateMyStudio(userId, UpdateStudioRequest("새 이름", "  NEW-URL  "))

        assertEquals("새 이름", updated.name)
        assertEquals("new-url", updated.galleryUrl)
    }

    @Test
    fun `주소를 그대로 두고 이름만 바꿀 수 있다`() {
        // 자기 주소가 자기 중복 검사에 걸리면 이름조차 못 바꾼다.
        val userId = signUp()
        studioService.create(userId, CreateStudioRequest("옛 이름", "keep-url", null))

        val updated = studioService.updateMyStudio(userId, UpdateStudioRequest("새 이름", "  KEEP-URL  "))

        assertEquals("새 이름", updated.name)
        assertEquals("keep-url", updated.galleryUrl)
    }

    @Test
    fun `남이 쓰는 주소로는 바꿀 수 없다`() {
        studioService.create(signUp(), CreateStudioRequest("남의 스튜디오", "someone-else", null))
        val userId = signUp()
        studioService.create(userId, CreateStudioRequest("내 스튜디오", "mine", null))

        val exception = assertFailsWith<StudioException> {
            studioService.updateMyStudio(userId, UpdateStudioRequest("내 스튜디오", "someone-else"))
        }

        assertEquals(StudioErrorCode.GALLERY_URL_DUPLICATED, exception.errorCode)
    }

    @Test
    fun `주소 중복 확인은 생성과 같은 규칙을 쓴다`() {
        val available = studioService.checkGalleryUrl("  FREE-URL  ")

        assertEquals("free-url", available.galleryUrl)
        assertTrue(available.available)

        studioService.create(signUp(), CreateStudioRequest("스튜디오", "free-url", null))

        assertFalse(studioService.checkGalleryUrl("free-url").available)
    }

    @Test
    fun `확인 단계에서도 쓸 수 없는 주소는 거절한다`() {
        // 여기서 통과했는데 저장이 거절되면 사용자는 이유를 알 수 없다.
        assertFailsWith<StudioException> { studioService.checkGalleryUrl("ab").available }
        assertFailsWith<StudioException> { studioService.checkGalleryUrl("studio url").available }
        assertFailsWith<StudioException> { studioService.checkGalleryUrl("studio_url").available }
        assertFailsWith<StudioException> { studioService.checkGalleryUrl("스튜디오").available }
        assertFailsWith<StudioException> { studioService.checkGalleryUrl("admin").available }
    }
}
