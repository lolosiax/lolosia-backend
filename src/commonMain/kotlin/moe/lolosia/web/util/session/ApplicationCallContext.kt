package moe.lolosia.web.util.session

import io.ktor.server.application.ApplicationCall

internal class ApplicationCallContext(override val call: ApplicationCall) : Context
