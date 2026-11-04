// 本文件原始构建配置：Codex，2026-11-04。
// 本次中文注释：DeepSeek Harness 补充，2026-11-04。
// 局部注释审计：Codex，2026-11-04；明确 JavaExec 配置只作用于相应 Gradle 任务。
// 本轮补充代码内部中文注释：DeepSeek Harness，2026-11-04；未改插件、依赖、任务或参数。

// Kotlin JVM 提供语言编译；application 生成可运行的安装包和启动脚本。
plugins {
    kotlin("jvm") version "2.2.21"
    application
}

// 只使用 Maven Central，保持原有依赖来源，避免引入额外仓库。
repositories { mavenCentral() }

// 运行期只依赖 JSON 序列化（事件日志）和 SQLite 驱动（V1 记忆），测试用 Kotlin test-junit。
dependencies {
    // 保持原有依赖：JSON 事件序列化和 SQLite 记忆，不新增日志框架。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    testImplementation(kotlin("test-junit"))
}

// 固定使用 JDK 21，避免不同机器上的编译器/JDK 行为差异。
kotlin { jvmToolchain(21) }
// 主类指向 MainKt（Main.kt 的顶层 main），安装脚本据此启动程序。
application { mainClass.set("logic.MainKt") }

// 测试通过系统属性把运行时 classpath 传给本地 CLI fixture 子进程。
// 这样 fixture 可以复用测试编译产物，而不需要额外打包或网络依赖。
tasks.test {
    doFirst {
        systemProperty("test.runtime.classpath", sourceSets["test"].runtimeClasspath.asPath)
    }
}

// Gradle run 等 JavaExec 任务保持标准输入可用，并统一 UTF-8 读写中文问题。
// 教师适配器依赖 stdin 传问题，CLI 也需要同一编码避免中文乱码。
tasks.withType<JavaExec>().configureEach {
    standardInput = System.`in`
    defaultCharacterEncoding = "UTF-8"
}
