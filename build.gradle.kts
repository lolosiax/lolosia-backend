import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("io.ebean") version "15.0.2"
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    kotlin("jvm") version "1.9.23"
    kotlin("kapt") version "1.9.23"
    kotlin("plugin.spring") version "1.9.23"
}

group = "top.lolosia"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.security:spring-security-crypto")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2")

    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    testImplementation("io.projectreactor:reactor-test")

    implementation("io.ebean:ebean:15.1.0") {
        exclude("io.ebean", "ebean-platform-all")
    }
    implementation("io.ebean:ebean-platform-mariadb:15.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.3.3")
    kapt("io.ebean:kotlin-querybean-generator:15.1.0")
    testImplementation("io.ebean:ebean-test:15.1.0")


    implementation("org.apache.commons:commons-collections4:4.4")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

ebean {
    debugLevel = 1
}


tasks.jar {
    archiveFileName = "${rootProject.name}-${rootProject.version}.jar"
    manifest {
        val at = attributes
        at["Main-Class"] = "top.lolosia.web.Boot"
        at["Implementation-Title"] = rootProject.name
        at["Implementation-Version"] = rootProject.version
    }
}

task("fatJarResources") {
    dependsOn(tasks.jar)
    dependsOn(":static:jar")
    doLast {
        // Classpath 文件列表
        val classpath = configurations.runtimeClasspath.get()
        val buildDir = layout.buildDirectory.asFile.get()
        val resDir = buildDir.resolve("tmp/fatJarResources")
        // Classpath 文件拷贝
        delete()
        copy {
            into(resDir.resolve("lib"))
            from(classpath.files)
        }
        val files = classpath.joinToString("\n") { it.name }
        val txt = resDir.resolve("lib/fileList.txt")
        txt.writeText(files)

        copy {
            into(resDir)
            val staticJar = project(":static").tasks.jar.get().outputs.files.singleFile
            from(zipTree(staticJar)) {
                exclude("META-INF/MANIFEST.MF")
            }
            from(zipTree(tasks.jar.get().outputs.files.singleFile))
        }
    }
}


tasks.create("fatJar", Jar::class.java) {
    group = "build"

    dependsOn("fatJarResources")
    archiveFileName = "${rootProject.name}-${rootProject.version}-fat.jar"

    val buildDir = layout.buildDirectory.asFile.get()

    from(buildDir.resolve("tmp/fatJarResources"))
    val classpath = configurations.runtimeClasspath.get()
    manifest {
        from(tasks.jar.get().manifest) {
            eachEntry {
                value = when (key) {
                    "Main-Class" -> "top.lolosia.web.Boot"
                    else -> return@eachEntry
                }
            }
        }
        attributes["Class-Path"] = classpath.joinToString(" ") { "lib/${it.name}" }
    }
}