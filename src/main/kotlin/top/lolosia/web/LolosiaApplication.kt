package top.lolosia.web

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.ConfigurableApplicationContext

@SpringBootApplication
class LolosiaApplication {
    companion object {
        lateinit var applicationContext: ConfigurableApplicationContext
            private set

        @JvmStatic
        fun main(args: Array<String>) {
            applicationContext = runApplication<LolosiaApplication>(*args)
        }
    }
}