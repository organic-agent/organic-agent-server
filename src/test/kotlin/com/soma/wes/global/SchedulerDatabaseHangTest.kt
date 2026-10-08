package com.soma.wes.global

import com.soma.wes.analysis.support.PipelineHeartbeat
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.support.CapturedLogs
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.TaskScheduler
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * DB가 응답을 멈췄을 때 앱의 주기 작업이 영구 정지하지 않는지 지키는 회귀 가드(#275).
 *
 * 2026-10-08 dev 사고: DB가 메모리 부족으로 응답을 멈춘 순간 나가 있던 쿼리가 시간 제한 없이 기다리며
 * 하나뿐인 스케줄러 스레드를 붙잡아, DB가 살아난 뒤에도 분석 스윕·업로드 확인 스윕·하트비트가 50분 넘게 멈췄다.
 * "응답은 없는데 연결은 열린" DB는 컨테이너 일시 정지(`docker pause`)로 만든다 — 끝나면 반드시 푼다.
 *
 * 세 층을 따로 본다: 치료(모든 쿼리 120초 안전망 · 스윕 쿼리 30초) → 격벽(스케줄러 스레드 3개).
 * 감지(하트비트의 멈춤 알림)는 `PipelineHeartbeatTest`가 본다.
 */
@IntegrationTest
class SchedulerDatabaseHangTest @Autowired constructor(
    private val dataSource: DataSource,
    private val postgres: PostgreSQLContainer<*>,
    private val taskScheduler: TaskScheduler,
    private val heartbeat: PipelineHeartbeat,
    private val photoPipelineRepository: PhotoPipelineRepository,
) {

    companion object {
        /** 모든 쿼리의 안전망 — 120초가 지나도 DB 답이 없으면 연결을 버린다. 설정 `application-api-runtime.yml`과 같아야 한다. */
        private const val SOCKET_TIMEOUT_MILLIS = 120_000

        /** 일시 정지한 DB에서 포기 동작만 빨리 보려고 이 연결에만 줄여 거는 시간. */
        private const val SHORT_SOCKET_TIMEOUT_MILLIS = 2_000

        /** 스윕 쿼리 30초 제한에 취소가 돌아오기까지의 여유를 더한 상한. */
        private val SWEEP_QUERY_GIVE_UP: Duration = Duration.ofSeconds(45)

        /** 막힌 작업들 옆에서 하트비트가 돌기까지 기다리는 시간. */
        private const val HEARTBEAT_WAIT_SECONDS = 10L
    }

    @Test
    fun `모든 DB 연결은 120초 동안 답이 없으면 포기하도록 설정돼 있다`() {
        // when
        val networkTimeout = dataSource.connection.use { it.networkTimeout }

        // then
        assertThat(networkTimeout).isEqualTo(SOCKET_TIMEOUT_MILLIS)
    }

    @Test
    fun `DB가 응답하지 않으면 나가 있던 쿼리는 포기 시간이 지나면 실패로 끝난다`() {
        dataSource.connection.use { connection ->
            // given — 살아 있는 연결을 쥔 채 DB가 응답을 멈춘다 (사고 때 스윕이 쿼리를 보낸 순간과 같다)
            connection.createStatement().use { it.execute("SELECT 1") }
            connection.setNetworkTimeout(Executors.newSingleThreadExecutor(), SHORT_SOCKET_TIMEOUT_MILLIS)
            pauseDatabase()
            try {
                // when & then — 무한 대기가 아니라 포기 시간 뒤 SQLException
                assertTimeoutPreemptively(Duration.ofMillis(SHORT_SOCKET_TIMEOUT_MILLIS * 5L)) {
                    assertThatThrownBy { connection.createStatement().use { it.execute("SELECT 1") } }
                        .isInstanceOf(SQLException::class.java)
                }
            } finally {
                unpauseDatabase()
            }
        }
    }

    @Test
    fun `스윕 쿼리는 30초 안에 끝나지 않으면 포기한다`() {
        dataSource.connection.use { locker ->
            // given — 다른 연결이 사진 표를 통째로 잠가 스윕 쿼리가 끝없이 기다리게 만든다
            locker.autoCommit = false
            locker.createStatement().use { it.execute("LOCK TABLE photos IN ACCESS EXCLUSIVE MODE") }
            try {
                // when & then
                assertTimeoutPreemptively(SWEEP_QUERY_GIVE_UP) {
                    assertThatThrownBy { photoPipelineRepository.countPending() }
                        .isInstanceOf(DataAccessException::class.java)
                }
            } finally {
                locker.rollback()
            }
        }
    }

    @Test
    fun `분석 스윕과 정리 작업이 함께 막혀 있어도 하트비트는 돈다`() {
        val release = CountDownLatch(1)
        val beaten = CountDownLatch(1)
        try {
            // given — 분석 스윕이 DB에서, 매시 정리 작업이 잠금에서 기다리듯 스케줄러 스레드 둘을 붙잡는다
            repeat(2) { taskScheduler.schedule({ release.await() }, Instant.now()) }

            CapturedLogs(PipelineHeartbeat::class).use { logs ->
                // when
                taskScheduler.schedule({ heartbeat.beat().also { beaten.countDown() } }, Instant.now().plusMillis(200))

                // then
                assertThat(beaten.await(HEARTBEAT_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
                assertThat(logs.eventsOf("sweep.heartbeat")).hasSize(1)
            }
        } finally {
            release.countDown()
        }
    }

    private fun pauseDatabase() {
        postgres.dockerClient.pauseContainerCmd(postgres.containerId).exec()
    }

    private fun unpauseDatabase() {
        postgres.dockerClient.unpauseContainerCmd(postgres.containerId).exec()
    }
}
