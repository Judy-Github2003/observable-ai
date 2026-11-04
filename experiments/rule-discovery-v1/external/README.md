# 外部审查与闭卷脚本

- `v2_external_blackbox_audit.py`：由外部审查者（云端 ChatGPT）编写，2026-11-04 收到。
- 本目录只做原样存档；**实现者不得修改脚本语义**。
- 脚本不 import 学生 Kotlin，也不复用仓库的 `tools/rule_discovery_v1.py`；隐藏规则只存在于脚本进程内。
- 运行前提：`./gradlew test installDist`，且 PATH 中有 `java`。

实现者自跑的输出不构成独立闭卷；只用于开发回归。正式闭卷必须由外部审查者用未公开种子重新执行，并保存原始输出。
