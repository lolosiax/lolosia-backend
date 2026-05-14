package moe.lolosia.web.controller.system

import moe.lolosia.web.service.system.MenuService
import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/menu")
class MenuController {

    @Autowired
    lateinit var service: MenuService

    @PostMapping("/config")
    fun getMenuConfig(ctx: Context): Bundle {
        return service.getMenuConfig()
    }
}