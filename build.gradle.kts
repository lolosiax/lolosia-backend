import com.fasterxml.jackson.databind.json.JsonMapper

buildscript {
    dependencies {
        classpath("com.fasterxml.jackson.core:jackson-databind:2.14.2")
    }
}

plugins {
    id("io.ebean") version "15.0.2"

    id("org.springframework.boot") version "3.5.6"
    // id("io.spring.dependency-management") version "1.1.7"

    id("dev.reformator.stacktracedecoroutinator") version "2.6.2"

    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")

    kotlin("jvm")
    kotlin("kapt")
    kotlin("plugin.spring")

    application

    id("moe.lolosia")
}

group = "moe.lolosia"
version = "1.0.0-SNAPSHOT"

dependencies {

    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")

    implementation("org.springframework.boot:spring-boot-starter-webflux:3.5.6")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf:3.5.6")
    implementation("org.springframework.boot:spring-boot-starter-mail:3.5.6")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf:3.5.6")
    implementation("org.springframework.security:spring-security-crypto:7.0.3")
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.6")
    developmentOnly("org.springframework.boot:spring-boot-starter-actuator:3.5.6")
    //developmentOnly("org.springframework.boot:spring-boot-devtools:3.5.6")

    // implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter:1.0.0-M6"){
    //     exclude("org.springframework.boot","spring-boot-starter")
    // }
    implementation("org.springframework.ai:spring-ai-client-chat:1.1.0-M4")
    implementation("org.springframework.ai:spring-ai-openai:1.1.0-M4")
    implementation("org.springframework.ai:spring-ai-anthropic:1.1.0-M4")
    implementation("com.alibaba.cloud.ai:spring-ai-alibaba-dashscope:1.1.2.2")
    implementation("org.springframework.ai:spring-ai-starter-mcp-client-webflux:1.1.0-M4")
    implementation("org.springframework.ai:spring-ai-milvus-store:1.1.0-M4")
    // implementation("org.springframework.ai:spring-ai-pdf-document-reader:1.1.0-M4")
    // implementation("org.springframework.ai:spring-ai-tika-document-reader:1.1.0-M4")

    implementation("org.apache.httpcomponents.client5:httpclient5:5.5.1")
    implementation("io.micrometer:micrometer-observation:1.14.6")

    implementation("com.github.docker-java:docker-java:3.4.0")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.4.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.4")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.4")

    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.2.2")
    testImplementation("io.projectreactor:reactor-test:3.6.5")


    implementation(project(":orm"))
    implementation("io.ebean:ebean:15.1.0") {
        exclude("io.ebean", "ebean-platform-all")
        exclude("io.ebean", "ebean-querybean")
    }
    implementation("io.ebean:ebean-platform-mariadb:15.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.3.3")
    kapt("io.ebean:kotlin-querybean-generator:15.1.0")
    testImplementation("io.ebean:ebean-test:15.1.0")

    implementation("co.elastic.clients:elasticsearch-java:8.18.2")

    implementation("cn.hutool:hutool-captcha:5.8.28")
    implementation("org.apache.commons:commons-collections4:4.4")
    implementation("org.jmdns:jmdns:3.5.12")

    // Kotlin Compose
    implementation(compose.desktop.currentOs)
    implementation("io.github.vinceglb:filekit-compose:0.8.7")
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

sourceSets {
    main {
        java {
            srcDir(projectDir.resolve("src/main/kotlin"))
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

ebean {
    debugLevel = 1
}

springBoot {
    mainClass = "moe.lolosia.web.LolosiaApplication"
}

tasks.jar {
    archiveFileName = "${rootProject.name}-${rootProject.version}.jar"
    manifest {
        val at = attributes
        at["Main-Class"] = "moe.lolosia.web.LolosiaApplication"
        at["Implementation-Title"] = rootProject.name
        at["Implementation-Version"] = rootProject.version
    }
}

tasks.bootJar {
    dependsOn(tasks.jar)
    archiveClassifier = "fat"
}


lolosia {
    packageName = "moe.lolosia.web"
    projectName = "洛洛希雅的小网站"

    val platformsFile = rootDir.resolve("platforms.json")

    @Suppress("UNCHECKED_CAST")
    val platformConfig = if (!platformsFile.exists()) emptyMap()
    else JsonMapper().readValue(platformsFile, Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    deploy {
        val deployConfig = platformConfig["deploy"] as? Map<String, Any?>

        activate = deployConfig?.get("active") as? String
        val servers = deployConfig?.get("servers") as? List<Map<String, Any?>>

        servers?.forEach { server ->
            server(server["name"] as String) {
                host = server["host"] as String
                port = (server["port"] as? Number)?.toInt() ?: 22
                user = server["user"] as String
                password = server["password"] as String
            }
        }
    }

    viteJar {
        val webPath = platformConfig["webPath"] as? String
        if (!webPath.isNullOrBlank()) {
            platform("default") {
                contextPath = "home"
                dir = webPath
            }
        }
    }
}