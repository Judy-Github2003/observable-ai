// 本文件原始构建配置：Codex，2026-11-04。
// 本次中文注释：DeepSeek Harness 补充，2026-11-04。
// 本轮补充代码内部中文注释：DeepSeek Harness，2026-11-04；未改仓库或项目名。

// 插件解析使用 Maven Central 和 Gradle 官方插件仓库，保持原依赖来源。
// 只影响插件解析，不改变应用运行期仓库。
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// 工程名用于生成的 IDE/构建产物标识，保持与原项目一致。
rootProject.name = "logic-learner"
