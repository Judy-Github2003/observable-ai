package logic.x01

import kotlin.test.Test

/**
 * JUnit 入口：把包内自带的 `X01ContractSmoke.kt` 顶层 main 纳入 Gradle test。
 * 这样契约检查与真实 B0 smoke 都会出现在同一份测试报告里，避免出现“脚本跑过但 CI 不知道”。
 */
class X01ContractSmokeRunner {
    @Test
    fun contractSmokeFourteenCases() {
        runContractSmoke()
    }
}

