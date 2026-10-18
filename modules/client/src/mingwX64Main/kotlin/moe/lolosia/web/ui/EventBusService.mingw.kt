package moe.lolosia.web.ui

import moe.lolosia.web.ui.client.runWindowsEventService

actual fun runEventBusService(console: Boolean): Int = runWindowsEventService(console)
