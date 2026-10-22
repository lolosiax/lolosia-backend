plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.14.2")
}

gradlePlugin {
    plugins {
        create("moe.thirdparty.publish") {
            id = "moe.thirdparty.publish"
            implementationClass = "moe.lolosia.thirdparty.ThirdpartyPublishPlugin"
        }
    }
}
