plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("moe.lolosia") {
            id = "moe.lolosia"
            implementationClass = "moe.lolosia.gradle.LolosiaPlugin"
        }
    }
}

// pluginDescriptors 与 processResources 都会产出 META-INF/gradle-plugins/*.properties
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

dependencies {
    implementation("org.hidetake.ssh:org.hidetake.ssh.gradle.plugin:2.11.2")
    implementation("org.hidetake:groovy-ssh:2.11.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.14.2")
}