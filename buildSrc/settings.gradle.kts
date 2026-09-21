// buildSrc 的构建脚本默认看不到主工程的版本目录，这里显式挂载，
// 避免 Kotlin 版本号在主工程与 buildSrc 两处分别维护（KGP 版本必须一致）。
dependencyResolutionManagement {
    versionCatalogs {
        create("libs").from(files("../gradle/libs.versions.toml"))
    }
}
