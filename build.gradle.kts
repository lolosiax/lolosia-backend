import org.hidetake.groovy.ssh.core.Remote
import org.hidetake.groovy.ssh.core.RunHandler
import org.hidetake.groovy.ssh.session.SessionHandler
import tools.jackson.databind.json.JsonMapper

plugins {
    id("io.ebean") version "15.0.2"

    id("org.springframework.boot") version "4.0.3"
    // id("io.spring.dependency-management") version "1.1.7"

    id("org.hidetake.ssh") version "2.11.2"

    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")

    kotlin("jvm")
    kotlin("kapt")
    kotlin("plugin.spring")

    application
}

group = "top.lolosia"
version = "1.0.0-SNAPSHOT"

dependencies {

    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")

    implementation("org.springframework.boot:spring-boot-starter-webflux:4.0.3")
    implementation("org.springframework.boot:spring-boot-starter-mail:4.0.3")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf:4.0.3")
    implementation("org.springframework.security:spring-security-crypto:7.0.3")
    testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.3")
    developmentOnly("org.springframework.boot:spring-boot-starter-actuator:4.0.3")
    //developmentOnly("org.springframework.boot:spring-boot-devtools:4.0.3")

    // implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter:1.0.0-M6"){
    //     exclude("org.springframework.boot","spring-boot-starter")
    // }
    implementation("org.springframework.ai:spring-ai-client-chat:2.0.0-M2")
    implementation("org.springframework.ai:spring-ai-openai:2.0.0-M2")
    implementation("org.springframework.ai:spring-ai-anthropic:2.0.0-M2")
    implementation("org.springframework.ai:spring-ai-starter-mcp-client-webflux:2.0.0-M2")
    implementation("org.springframework.ai:spring-ai-milvus-store:2.0.0-M2")
    // implementation("org.springframework.ai:spring-ai-pdf-document-reader:1.1.0-M4")
    // implementation("org.springframework.ai:spring-ai-tika-document-reader:2.0.0-M2")

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
    dependsOn(":static:jar")
    archiveClassifier = "fat"

    val staticJar = project(":static").tasks.jar.get().outputs.files.singleFile
    from(zipTree(staticJar)) {
        exclude("META-INF/MANIFEST.MF")
        into("BOOT-INF/classes")
    }
}

tasks.create("deploy") {
    group = "deploy"
    dependsOn(tasks.bootJar)
    doLast {

        val file = rootDir.resolve("platforms.json")
        val text = if (!file.exists()) """{"ssh": {}}"""
        else file.readText()

        @Suppress("UNCHECKED_CAST")
        var map = JsonMapper().readValue(text, Map::class.java) as MutableMap<String, Any>
        @Suppress("UNCHECKED_CAST")
        map = map["ssh"] as? MutableMap<String, Any> ?: mutableMapOf()
        map["name"] = "Server"
        if ("host" !in map) map["host"] = "localhost"
        if ("port" !in map) map["port"] = 22
        if ("user" !in map) map["user"] = "root"
        if ("password" !in map) map["password"] = "123456"

        val remote = Remote(map)
        ssh.run(delegateClosureOf<RunHandler> {
            session(remote, delegateClosureOf<SessionHandler> {

                val pwd = "/srv/lolosia-backend/docker_build/"

                execute("mkdir -p $pwd")
                execute("cd $pwd && rm -rf *-fat.jar")

                put(
                    hashMapOf(
                        "from" to rootDir.resolve("Dockerfile"),
                        "into" to "${pwd}Dockerfile"
                    )
                )

                val jarFile = tasks.bootJar.get().outputs.files.singleFile
                put(
                    hashMapOf(
                        "from" to jarFile,
                        "into" to "$pwd${jarFile.name}"
                    )
                )
                val exits = execute(
                    hashMapOf("ignoreError" to true),
                    "cd $pwd && ls | cat"
                ).split("\n").any { it == "docker_shell.sh" }
                if (!exits) {
                    put(
                        hashMapOf(
                            "from" to rootDir.resolve("docker_shell.sh"),
                            "into" to "${pwd}docker_shell.sh"
                        )
                    )
                }

                execute("cd $pwd && bash docker_shell.sh")
            })
        })
    }
}