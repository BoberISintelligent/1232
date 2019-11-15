package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid


private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AdocPreviewController(private val asciiService: AsciiService) {


    data class ReqResp(@JsonProperty("content") val content: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN", "ROLE_STUDENT")
    @PostMapping("/preview/adoc")
    fun controller(@Valid @RequestBody dto: ReqResp, caller: EasyUser): ReqResp {
        log.debug { "${caller.id} is testing asciidoc with content: ${dto.content}." }

        return ReqResp(asciiService.adocToHtml(dto.content))
    }
}