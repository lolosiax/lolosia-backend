package top.lolosia.web.service.system

import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.bundle.bundleScope
import org.springframework.stereotype.Service

@Service
class MenuService {
    fun getMenuConfig(): Bundle {
        return bundleScope {
            // default 始终应该是 true
            "default" set true
        }
    }
}