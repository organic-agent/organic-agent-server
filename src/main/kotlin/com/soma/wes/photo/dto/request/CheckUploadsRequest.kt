package com.soma.wes.photo.dto.request

import com.soma.wes.photo.domain.Photo
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(description = "올리기 전에 원본들이 이 갤러리에 이미 있는지 묻는다. 리사이즈보다 먼저 부른다 — 이미 올라온 사진을 리사이즈하지 않기 위해서다.")
data class CheckUploadsRequest(

    @field:NotEmpty
    @field:Size(max = MAX_SOURCE_HASHES)
    @field:Schema(
        description = "원본의 지문 목록. 지문은 `{원본 바이트 크기}-{원본 앞 64KB 의 CRC32C 를 소문자 hex 8자로}`. "
            + "한 번에 $MAX_SOURCE_HASHES 개까지이고, 더 많으면 나눠 부른다.",
        example = "[\"18432000-c1d44383\"]",
    )
    val sourceHashes: List<@Pattern(regexp = Photo.SOURCE_HASH_PATTERN) String>,
) {

    companion object {
        /** 한 번에 물을 수 있는 지문 수. 조회만 하므로 발급 배치(`app.storage.max-batch-size`)보다 크게 잡았다. */
        const val MAX_SOURCE_HASHES = 1000
    }
}
