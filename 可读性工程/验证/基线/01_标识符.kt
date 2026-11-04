fun 问候(名字: String): String {
    val 完整句子 = "你好，" + 名字
    return 完整句子
}

fun main() {
    val 结果 = 问候("世界")
    println(结果)
}
