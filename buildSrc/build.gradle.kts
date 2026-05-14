plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.hidetake.ssh:org.hidetake.ssh.gradle.plugin:2.11.2")
    implementation("org.hidetake:groovy-ssh:2.11.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.14.2")
}