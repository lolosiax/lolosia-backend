plugins {
    // 版本由根项目（alias(libs.plugins.kotlin.multiplatform)）带入 classpath，这里不重复声明版本
    kotlin("jvm")
}

group = "moe.lolosia"
version = "1.0.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ksp.symbol.processing.api)
}
