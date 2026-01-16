package top.lolosia.web.controller.system

import top.lolosia.web.service.system.MenuService
import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.session.Context
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