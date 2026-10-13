package com.soma.wes.activity.support

import com.soma.wes.activity.infrastructure.InMemoryActivityBuffer
import com.soma.wes.activity.repository.ActivityRepository
import com.soma.wes.activity.service.ActivityFlushService
import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.activity.service.port.ActivityBuffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock

/** 스위치(`app.activity.buffer.enabled`)가 메모장·플러시를 켜고 끄는지. 통합 테스트 컨텍스트는 꺼진 쪽이라 켜진 조합을 여기서 본다. */
class ActivityBufferWiringTest {

    private val runner = ApplicationContextRunner()
        .withBean(ActivityRepository::class.java, { mock<ActivityRepository>() })
        .withBean(Clock::class.java, { Clock.systemDefaultZone() })
        .withUserConfiguration(
            InMemoryActivityBuffer::class.java,
            ActivityFlushService::class.java,
            ActivityFlushScheduler::class.java,
            ActivityRecorder::class.java,
        )

    @Test
    fun `켜면 메모장과 주기 플러시가 생긴다`() {
        runner.withPropertyValues("app.activity.buffer.enabled=true").run { context ->
            assertThat(context).hasSingleBean(ActivityBuffer::class.java)
            assertThat(context).hasSingleBean(ActivityFlushScheduler::class.java)
            assertThat(context).hasSingleBean(ActivityRecorder::class.java)
        }
    }

    @Test
    fun `끄면 메모장 없이 기록기만 남아 바로 쓴다`() {
        runner.run { context ->
            assertThat(context).doesNotHaveBean(ActivityBuffer::class.java)
            assertThat(context).doesNotHaveBean(ActivityFlushScheduler::class.java)
            assertThat(context).hasSingleBean(ActivityRecorder::class.java)
        }
    }
}
