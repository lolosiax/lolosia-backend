package moe.lolosia.web.util.elastic

import com.fasterxml.jackson.annotation.JsonFormat
import java.io.Serializable
import java.time.Instant
import java.util.*

abstract class ElasticsearchModel : Serializable {
    lateinit var id: UUID

    var createdBy: UUID? = null

    var updatedBy: UUID? = null

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    var createdAt: Instant? = null

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    var updatedAt: Instant? = null

    var deleted = false
}