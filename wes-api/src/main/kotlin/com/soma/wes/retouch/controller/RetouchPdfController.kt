package com.soma.wes.retouch.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.retouch.controller.docs.RetouchPdfControllerDocs
import com.soma.wes.retouch.service.RetouchPdfService
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/retouch")
class RetouchPdfController(private val service: RetouchPdfService) : RetouchPdfControllerDocs {
    @GetMapping("/rounds/{roundNo}/requests.pdf")
    override fun download(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable roundNo: Int,
        @RequestParam(defaultValue = "all") scope: String,
    ): ResponseEntity<ByteArray> {
        val result = service.download(galleryId = galleryId, roundNo = roundNo, userId = loginUser.id, scope = scope)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(result.filename, StandardCharsets.UTF_8).build().toString())
            .contentLength(result.bytes.size.toLong())
            .body(result.bytes)
    }
}
