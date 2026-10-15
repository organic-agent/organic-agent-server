package com.soma.wes.global.cluster

import com.soma.wes.activity.support.ActivityFlushScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import org.springframework.scheduling.annotation.Scheduled

/**
 * 서버가 여러 대가 되면 `@Scheduled` 작업은 리더 한 대만 돌아야 한다(#313 ADR-0002). 새 주기 작업이 [SchedulerLeadership]을 받지 않으면
 * 서버 수만큼 겹쳐 돈다 — 이 테스트가 그 실수를 막는다. 서버마다 돌아야 하는 작업만 [EVERY_SERVER]에 까닭과 함께 둔다.
 */
class ScheduledLeadershipGuardTest {

    companion object {
        private const val CLASSES = "classpath*:com/soma/wes/**/*.class"

        /** 리더를 보지 않고 모든 서버에서 도는 작업. */
        private val EVERY_SERVER = setOf(
            // 활동 시각 버퍼는 서버마다 자기 메모리에 있다 — 리더만 비우면 다른 서버의 버퍼는 영영 안 써진다.
            ActivityFlushScheduler::class.java,
        )
    }

    @Test
    fun `주기 작업은 리더 임대를 확인하거나 서버마다 도는 까닭이 적혀 있다`() {
        val unguarded = scheduledClasses()
            .filterNot { it in EVERY_SERVER }
            .filterNot { type -> type.declaredConstructors.any { SchedulerLeadership::class.java in it.parameterTypes } }

        assertThat(unguarded.map { it.name }).isEmpty()
    }

    @Test
    fun `스캔이 실제 스케줄러들을 찾는다`() {
        assertThat(scheduledClasses().map { it.simpleName })
            .contains("AnalysisPipelineScheduler", "TrashPurgeScheduler", "ActivityFlushScheduler", "AiSelectionJobRecovery")
    }

    /** 클래스 파일을 직접 읽는다 — 컴포넌트 스캔은 `@ConditionalOnProperty`가 꺼진 빈(활동 버퍼 등)을 건너뛰어 검사에서 빠뜨린다. */
    private fun scheduledClasses(): List<Class<*>> {
        val readers = CachingMetadataReaderFactory()
        return PathMatchingResourcePatternResolver().getResources(CLASSES)
            .map { readers.getMetadataReader(it).annotationMetadata }
            .filter { it.hasAnnotatedMethods(Scheduled::class.java.name) }
            .map { Class.forName(it.className) }
    }
}
