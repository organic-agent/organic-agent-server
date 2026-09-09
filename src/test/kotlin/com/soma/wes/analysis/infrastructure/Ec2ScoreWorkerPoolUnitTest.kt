package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.ScoreWorkerStateDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse
import software.amazon.awssdk.services.ec2.model.Instance
import software.amazon.awssdk.services.ec2.model.InstanceState
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.Reservation
import software.amazon.awssdk.services.ec2.model.StartInstancesRequest
import software.amazon.awssdk.services.ec2.model.StartInstancesResponse
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest
import software.amazon.awssdk.services.ec2.model.StopInstancesResponse

class Ec2ScoreWorkerPoolUnitTest {

    private val ec2Client = mock<Ec2Client>()
    private val pool = Ec2ScoreWorkerPool(
        ec2Client,
        AnalysisProperties(gpu = AnalysisProperties.Gpu(enabled = true, tag = "wes-score-gpu")),
    )

    private fun instance(id: String, state: InstanceStateName, launchedAt: Instant? = null): Instance = Instance.builder()
        .instanceId(id)
        .state(InstanceState.builder().name(state).build())
        .launchTime(launchedAt)
        .build()

    private fun describes(vararg instances: Instance) {
        whenever(ec2Client.describeInstances(any<DescribeInstancesRequest>())).thenReturn(
            DescribeInstancesResponse.builder().reservations(Reservation.builder().instances(*instances).build()).build(),
        )
    }

    @Test
    fun `Name 태그로 찾은 인스턴스를 네 상태로 접어 돌려준다`() {
        val launched = Instant.parse("2026-09-08T00:00:00Z")
        describes(
            instance("i-run", InstanceStateName.RUNNING, launched),
            instance("i-stop", InstanceStateName.STOPPED),
            instance("i-gone", InstanceStateName.TERMINATED),
        )

        val snapshot = pool.snapshot()

        val request = argumentCaptor<DescribeInstancesRequest>()
        verify(ec2Client).describeInstances(request.capture())
        assertSoftly { softly ->
            softly.assertThat(request.firstValue.filters().single().name()).isEqualTo("tag:Name")
            softly.assertThat(request.firstValue.filters().single().values()).containsExactly("wes-score-gpu")
            softly.assertThat(snapshot.map { it.instanceId to it.state }).containsExactly(
                "i-run" to ScoreWorkerStateDto.RUNNING,
                "i-stop" to ScoreWorkerStateDto.STOPPED,
                "i-gone" to ScoreWorkerStateDto.STOPPED,
            )
            softly.assertThat(snapshot.first().launchedAt?.toInstant()).isEqualTo(launched)
            softly.assertThat(snapshot.first().isUp).isTrue()
        }
    }

    @Test
    fun `켜기는 꺼진 인스턴스 하나만 켜고 없으면 아무것도 하지 않는다`() {
        whenever(ec2Client.startInstances(any<StartInstancesRequest>())).thenReturn(StartInstancesResponse.builder().build())
        describes(instance("i-run", InstanceStateName.RUNNING), instance("i-a", InstanceStateName.STOPPED), instance("i-b", InstanceStateName.STOPPED))

        pool.start()

        val request = argumentCaptor<StartInstancesRequest>()
        verify(ec2Client).startInstances(request.capture())
        assertThat(request.firstValue.instanceIds()).containsExactly("i-a")

        describes(instance("i-run", InstanceStateName.RUNNING))
        pool.start()
        verify(ec2Client).startInstances(any<StartInstancesRequest>())
    }

    @Test
    fun `끄기는 지정한 인스턴스만 끈다`() {
        whenever(ec2Client.stopInstances(any<StopInstancesRequest>())).thenReturn(StopInstancesResponse.builder().build())

        pool.stop("i-run")

        val request = argumentCaptor<StopInstancesRequest>()
        verify(ec2Client).stopInstances(request.capture())
        assertThat(request.firstValue.instanceIds()).containsExactly("i-run")
        verify(ec2Client, never()).describeInstances(any<DescribeInstancesRequest>())
    }

    @Test
    fun `설정이 꺼져 있으면 풀이 없고 SDK 예외는 도메인 예외다`() {
        assertThat(Ec2ScoreWorkerPool(ec2Client, AnalysisProperties()).isAvailable).isFalse()
        assertThat(pool.isAvailable).isTrue()

        whenever(ec2Client.describeInstances(any<DescribeInstancesRequest>())).thenThrow(SdkClientException.create("no credentials"))
        assertThatThrownBy { pool.snapshot() }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.SCORE_WORKER_CONTROL_FAILED)
    }
}
