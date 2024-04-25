package top.lolosia.web.util

import com.fasterxml.jackson.databind.json.JsonMapper
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

private val mapper = JsonMapper()

fun success(msg: String = "success"): Any = mapper.writeValueAsString(msg)

/**
 * Constructor with a given message
 */
fun ErrorResponseException(status: HttpStatusCode, msg: String, e: Throwable? = null): ErrorResponseException {
    return ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, msg), e)
}