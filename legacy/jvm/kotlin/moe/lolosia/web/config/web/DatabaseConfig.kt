package moe.lolosia.web.config.web

import io.ebean.Database
import io.ebean.datasource.DataSourceBuilder
import io.ktor.server.application.Application
import moe.lolosia.web.util.ebean.LazyDatabase
import moe.lolosia.web.util.packageLogger
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val logger = packageLogger()

/** 数据库名：使用**命名数据库**而非默认库（规避 Ebean 默认库静态初始化中毒问题） */
const val DEFAULT_DB_NAME = "db"

/**
 * Ebean 数据库配置（适配 Ktor/Koin 与 Ebean 19 的 DataSourceBuilder API）。
 *
 * 关键设计：
 * 1. **命名数据库 + isDefaultServer=false**：Ebean 的默认库走静态初始化，数据库宕机时
 *    初始化失败会被 JVM 永久缓存（NoClassDefFoundError），之后永远无法再连接；
 *    命名库 + [LazyDatabase] 懒代理则不会。
 * 2. 数据源配置读取 application.yaml 的 `datasource.db.*`。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
fun Application.databaseModule(): Module {
    val config = environment.config
    fun prop(path: String, default: String): String =
        config.propertyOrNull(path)?.getString() ?: default

    return databaseModule(
        url = prop("datasource.db.url", "jdbc:mariadb://localhost:3306/ktor_template"),
        username = prop("datasource.db.username", "root"),
        password = prop("datasource.db.password", "123456"),
        driver = prop("datasource.db.driver", "org.mariadb.jdbc.Driver"),
    )
}

fun databaseModule(url: String, username: String, password: String, driver: String): Module {
    val lazyDb = LazyDatabase {
        buildDatabase(DEFAULT_DB_NAME, url, username, password, driver)
    }
    return module {
        single<Database>(named(DEFAULT_DB_NAME)) { lazyDb.proxy() }
    }
}

/** 程序化构建 Ebean Database（非默认库、关 L2 缓存以适配 RK3566 有限内存） */
private fun buildDatabase(name: String, url: String, username: String, password: String, driver: String): Database {
    logger.info("初始化 Ebean 数据库 '{}'（非默认库，懒加载）", name)
    val dataSource = DataSourceBuilder.create()
        .url(url)
        .username(username)
        .password(password)
        .driver(driver)
    return Database.builder()
        .dataSourceBuilder(dataSource)
        .setName(name)
        .setDefaultServer(false)
        .disableL2Cache(true)
        .build()
}
