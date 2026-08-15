package com.soma.wes.studio.fixture

import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.TestSequence
import com.soma.wes.user.domain.User
import com.soma.wes.user.fixture.UserFixture
import org.springframework.stereotype.Component

@Component
class StudioFixture(
    private val studioRepository: StudioRepository,
    private val userFixture: UserFixture,
) {

    /** 스튜디오를 가진 사용자. 갤러리 생성 API는 스튜디오가 있어야 지나간다. */
    fun 작가(): User {
        val user = userFixture.사용자()
        스튜디오(user)
        return user
    }

    fun 스튜디오(owner: User): Studio = studioRepository.save(
        Studio(userId = owner.id!!, name = "테스트 스튜디오", galleryUrl = "studio-${TestSequence.next()}"),
    )
}
