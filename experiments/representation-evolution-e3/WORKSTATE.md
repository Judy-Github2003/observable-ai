# E3 工作状态（交接便笺）

```text
E3 stage: E3-A
current_task: A1 unique patch (A1-FIX-01/02)
research_direction: A
authorization: A1 patch only; no A2/B1/C1/D
last_verified_code_commit: 795a2057786236256edd4b58c57b677248f4f100
base_b0_sha: 0ca4561309aeb914df6e3f2c3877a50b080bf731
working_tree: clean
last_test_command: JAVA_HOME=C:\Users\j1437\.jdks\jbr-21.0.11; .\gradlew.bat --offline --no-daemon test
last_test_result: 292 passed / 292 total, 0 failed（B0 173 + E3 119）
verification_source: implementer_run
independent_closed_book: not_run
known_defects: none known for the two A1 patch items
patch_count: 1/1
test_task_status:
  M1.0: REVIEW
  M1.1a: ACCEPTED_LIMITED
  A1: REVIEW (implementer tests passed; pending independent review)
  A2: EXPLORATORY (not authorized)
  B1: EXPLORATORY (not authorized)
  C1: EXPLORATORY (not authorized)
  D: EXPLORATORY (not authorized)
paused_reason: none
resume_conditions: n/a
next_authorized_action: none; wait for review
unauthorized: A2, B1, C1, D, M1.2+, A10-A12, E3-B/C
```

## A1 patch record

- A1-FIX-01：`authorizedInputs()` 增加字段值域校验；`"0"/"1"` 世界的 `"other"` 被拒绝。
- A1-FIX-02：新增 `ChallengeQueryLedger`；同评测实验多会话共享，按 sampleId 去重；初始已标注由评测端传入；相同 visible 不同 sampleId 仍允许。
- 测试：292/292；新增域值、跨会话重复、初始已标注、同 visible 异 sample 反例。
- 限制：同 JVM；无持久化；无可信历史时使用空 ledger，不宣称跨会话历史去重。

## 说明

- A8 三项修正：A6 截断/零幸存者分离；A8 探测证据核验；A7 先验证内容再消费 token。
- A9 评测基础设施冻结；B0 `0ca4561` 只读；E3-B/C 未授权。
- A2/B1/C1/D 均未启动，等待研究负责人单独授权。
