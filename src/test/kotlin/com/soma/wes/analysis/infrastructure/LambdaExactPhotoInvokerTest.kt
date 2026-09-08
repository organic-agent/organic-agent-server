package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.service.ExactPhotoProcessingRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.lambda.model.InvokeResponse
import tools.jackson.databind.json.JsonMapper

class LambdaExactPhotoInvokerTest {

    private val lambdaClient = mock<LambdaClient>()
    private val objectMapper = JsonMapper.builder().build()
    private val invoker = LambdaExactPhotoInvoker(
        lambdaClient,
        AnalysisProperties(embedderFunctionName = "wes-embedder"),
        objectMapper,
    )

    @Test
    fun `임베더 함수 이름이 있어야 사용할 수 있다`() {
        assertThat(invoker.isAvailable).isTrue()
        assertThat(LambdaExactPhotoInvoker(lambdaClient, AnalysisProperties(), objectMapper).isAvailable).isFalse()
    }

    @Test
    fun `exact photo 이벤트를 Lambda EVENT 호출 계약 그대로 직렬화한다`() {
        whenever(lambdaClient.invoke(any<InvokeRequest>())).thenReturn(InvokeResponse.builder().statusCode(202).build())
        val request = ExactPhotoProcessingRequest(
            jobId = 11,
            attemptCount = 2,
            jobType = "EMBEDDING",
            photoId = 31,
            galleryId = 41,
            storageKey = "galleries/41/revision-51.jpg",
            revisionId = 51,
        )

        invoker.invoke(request)

        val invocation = argumentCaptor<InvokeRequest>()
        verify(lambdaClient).invoke(invocation.capture())
        assertThat(invocation.firstValue.functionName()).isEqualTo("wes-embedder")
        assertThat(invocation.firstValue.invocationType()).isEqualTo(InvocationType.EVENT)
        val payload = objectMapper.readTree(invocation.firstValue.payload().asUtf8String())
        assertThat(payload["jobId"].asLong()).isEqualTo(11)
        assertThat(payload["attemptCount"].asInt()).isEqualTo(2)
        assertThat(payload["jobType"].asText()).isEqualTo("EMBEDDING")
        assertThat(payload["photoId"].asLong()).isEqualTo(31)
        assertThat(payload["galleryId"].asLong()).isEqualTo(41)
        assertThat(payload["storageKey"].asText()).isEqualTo("galleries/41/revision-51.jpg")
        assertThat(payload["revisionId"].asLong()).isEqualTo(51)
    }
}
