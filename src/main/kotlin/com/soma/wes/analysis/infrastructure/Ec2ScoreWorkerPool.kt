package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.ScoreWorkerDto
import com.soma.wes.analysis.dto.ScoreWorkerStateDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.ScoreWorkerPool
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ec2.model.Instance
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.StartInstancesRequest
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest

/**
 * 운영 워커 풀 — `Name` 태그가 [AnalysisProperties.Gpu.tag]인 EC2 인스턴스들. 로컬 프로필에서는 [LocalProcessScoreWorkerPool]이 이 자리를 대신한다.
 * 인스턴스는 인프라(Terraform)가 만들고 여기서는 켜고 끄기만 한다. 태그가 붙은 인스턴스가 하나도 없으면 풀은 "없음"이다.
 */
@Component
@Profile("!local")
class Ec2ScoreWorkerPool(
    private val ec2Client: Ec2Client,
    private val properties: AnalysisProperties,
) : ScoreWorkerPool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val isAvailable: Boolean
        get() = properties.gpu.enabled && properties.gpu.tag.isNotBlank()

    override fun snapshot(): List<ScoreWorkerDto> = describe().map { instance ->
        ScoreWorkerDto(
            instanceId = instance.instanceId(),
            state = stateOf(instance.state().name()),
            launchedAt = instance.launchTime()?.let { ZonedDateTime.ofInstant(it, ZoneOffset.UTC) },
        )
    }

    override fun start() {
        val stopped = describe().firstOrNull { it.state().name() == InstanceStateName.STOPPED } ?: run {
            log.warn("켤 GPU 워커가 없다: tag={}", properties.gpu.tag)
            return
        }
        call("StartInstances", stopped.instanceId()) {
            ec2Client.startInstances(StartInstancesRequest.builder().instanceIds(stopped.instanceId()).build())
        }
        log.info("GPU 워커 켬: instance={} tag={}", stopped.instanceId(), properties.gpu.tag)
    }

    override fun stop(instanceId: String) {
        call("StopInstances", instanceId) {
            ec2Client.stopInstances(StopInstancesRequest.builder().instanceIds(instanceId).build())
        }
        log.info("GPU 워커 끔: instance={} tag={}", instanceId, properties.gpu.tag)
    }

    private fun describe(): List<Instance> = call("DescribeInstances", properties.gpu.tag) {
        ec2Client.describeInstances(
            DescribeInstancesRequest.builder()
                .filters(Filter.builder().name(NAME_TAG_FILTER).values(properties.gpu.tag).build())
                .build(),
        ).reservations().flatMap { it.instances() }
    }

    private fun <T> call(operation: String, target: String, block: () -> T): T = try {
        block()
    } catch (e: SdkException) {
        // 계산 실패가 아니라 호출 실패다(권한·API). 스윕이 잡아 다음 걸음에 다시 본다.
        log.error("EC2 {} 실패: target={}", operation, target, e)
        throw AnalysisException(AnalysisErrorCode.SCORE_WORKER_CONTROL_FAILED)
    }

    companion object {
        /** EC2 DescribeInstances 의 태그 필터 이름 — `tag:<키>`. 인프라가 워커에 `Name` 태그를 붙인다. */
        const val NAME_TAG_FILTER = "tag:Name"

        fun stateOf(name: InstanceStateName?): ScoreWorkerStateDto = when (name) {
            InstanceStateName.PENDING -> ScoreWorkerStateDto.PENDING
            InstanceStateName.RUNNING -> ScoreWorkerStateDto.RUNNING
            InstanceStateName.STOPPING -> ScoreWorkerStateDto.STOPPING
            else -> ScoreWorkerStateDto.STOPPED
        }
    }
}
