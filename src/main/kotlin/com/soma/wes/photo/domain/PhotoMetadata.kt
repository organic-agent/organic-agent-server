package com.soma.wes.photo.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.LocalDateTime

/**
 * 사진 한 장의 촬영 정보. [Photo]의 컬럼들이지만 하나의 값으로 묶어 둔다.
 *
 * [Photo]가 채우지 않는다. 이 서버는 이미지 바이트를 만지지 않으므로 EXIF를 읽을 방법이 없고,
 * 원본을 이미 디코딩해 들고 있는 임베딩 Lambda가 벡터·파생본과 같은 UPDATE에 함께 싣는다.
 * [Photo.applyMetadata]는 그 경로를 쓰지 않는 Mock 갤러리 복제와 테스트를 위해 둔다.
 *
 * 모든 필드가 nullable인 이유는 둘이다. 아직 Lambda가 돌지 않았거나(PENDING·UPLOADED),
 * 파일에 EXIF가 아예 없거나(스크린샷·편집본). 뒤쪽이면 [width]·[height]·[byteSize]만 채워진다.
 */
@Embeddable
class PhotoMetadata(

    /**
     * 촬영 시각. 타임존이 없는 것이 맞다.
     *
     * EXIF의 `DateTimeOriginal`에는 오프셋이 없고 카메라가 그 순간의 벽시계를 적을 뿐이다.
     * 서버 타임존으로 해석해 [java.time.ZonedDateTime]으로 담으면 여행지에서 찍은 사진이
     * 조용히 9시간 옮겨간다. 찍힌 그대로 두고 해석은 화면에 맡긴다.
     */
    @Column(name = "taken_at")
    val takenAt: LocalDateTime? = null,

    @Column(name = "camera_make", length = 100)
    val cameraMake: String? = null,

    @Column(name = "camera_model", length = 100)
    val cameraModel: String? = null,

    /** `1/200` 같은 유리수 표기 그대로. 초 단위 실수로 바꾸면 화면에 되돌릴 때 오차가 붙는다. */
    @Column(name = "exposure_time", length = 30)
    val exposureTime: String? = null,

    @Column(name = "f_number")
    val fNumber: Double? = null,

    @Column(name = "iso")
    val iso: Int? = null,

    /** EXIF 회전을 반영한 뒤의 가로. 사람이 보는 방향과 같다. */
    @Column(name = "width")
    val width: Int? = null,

    @Column(name = "height")
    val height: Int? = null,

    @Column(name = "byte_size")
    val byteSize: Long? = null,
) {

    /**
     * 채워진 값이 하나도 없는지.
     *
     * Hibernate는 컬럼이 전부 NULL이면 [Photo.metadata]를 null로 돌려주므로 정상 경로에서는
     * 이 값이 true가 되지 않는다. 다만 그 동작에만 기대면, 값 하나가 지워졌을 때 화면이
     * "촬영 정보 있음"이라고 말하면서 빈 칸만 늘어놓게 된다. 응답을 만드는 쪽이 여기를 본다.
     */
    val isEmpty: Boolean
        get() = takenAt == null && cameraMake == null && cameraModel == null &&
            exposureTime == null && fNumber == null && iso == null &&
            width == null && height == null && byteSize == null
}
