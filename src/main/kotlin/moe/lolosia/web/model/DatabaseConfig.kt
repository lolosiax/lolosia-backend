package moe.lolosia.web.model

import moe.lolosia.web.util.isClient
import io.ebean.Database
import io.ebean.DatabaseFactory
import io.ebeaninternal.api.SpiEbeanServer
import org.springframework.cglib.proxy.Enhancer
import org.springframework.cglib.proxy.MethodInterceptor
import org.springframework.cglib.proxy.MethodProxy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.lang.reflect.Method

@Configuration
class DatabaseConfig {
    @Bean("db")
    fun getDefaultDatabase() = database("db")

    private fun database(name: String): Database {
        return serverModeLoader {
            DatabaseFactory.create(name)
        }
    }

    private fun serverModeLoader(block: () -> Database): Database {
        val eh = Enhancer()
        eh.setSuperclass(SpiEbeanServer::class.java)
        eh.setCallback(ServerModeInterceptor(block))
        val db = eh.create()
        return db as Database
    }

    private class ServerModeInterceptor(val block: () -> Database) : MethodInterceptor {

        private var db: Database? = null

        @Synchronized
        fun getDb(): Database {
            if (db == null) {
                db = block()
            }
            return db!!
        }

        override fun intercept(obj: Any, method: Method, args: Array<out Any>, proxy: MethodProxy): Any? {

            if (isClient) {
                if (method.name == "shutdown") {
                    db?.let {
                        proxy.invoke(it, args)
                    }
                    return Unit
                }
                throw IllegalStateException("The application mode is not server.")
            }

            return proxy.invoke(getDb(), args)
        }
    }
}