// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：C8-03 测试共享一次基础能力构建，避免每个测试重复学习 P/Q/R。
package logic.e3.pack02

object C8TestBases {
    val bases: C8Bases by lazy { C8Bases() }
}
