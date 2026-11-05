# E3 工作状态（交接便笺）

```text
E3 stage: E3-A
current_task: C1 minimum predicate learning/reuse
research_direction: C
authorization: C1 only; no C2/A2/B1/D
last_verified_code_commit: 6a8fdc9384de00b0e515b36df0410803f9ce68a5
base_b0_sha: 0ca4561309aeb914df6e3f2c3877a50b080bf731
working_tree: clean
last_test_command: JAVA_HOME=C:\Users\j1437\.jdks\jbr-21.0.11; .\gradlew.bat --offline --no-daemon test
last_test_result: 295 passed / 295 total, 0 failed（B0 173 + E3 122）
verification_source: implementer_run
independent_closed_book: not_run
patch_count: C1 0/1
test_task_status:
  A1: ACCEPTED_LIMITED
  C1: REVIEW (implementer tests passed; pending independent review)
  A2: EXPLORATORY (not authorized)
  B1: EXPLORATORY (not authorized)
  C2: EXPLORATORY (not authorized)
  D: EXPLORATORY (not authorized)
known_defects:
  C1_P_REUSE_generated_more_candidates_than_baseline
  C1_result_is_single_fixed_world
  C1_no_independent_closed_book
paused_reason: none
resume_conditions: n/a
next_authorized_action: none; wait for C1 review
unauthorized: C2, A2, B1, D, A10-A12, E3-B/C broader work
```

## C1 record

- 第一任务：Composer 381，Filter IDENTIFIED，选中 `Or(Eq(a,"1"), Eq(b,"1"))`，heldout 5/5，B0 验证/激活通过。
- 第二任务：P_REUSE 536 candidates / IDENTIFIED / 3/3；NO_PREDICATE 180 / REFUSED / 0/3。
- 受限深度收益成立；计算成本优势不成立。
- 失败：heldout 翻转不激活；错误 contentHash 的 Call 被 B0 拒绝。
- 归因：Composer 是 designer_primitive；表达式是 student_composed。

## 说明

- A1 已受限验收，补丁额度 1/1 已使用；不宣称独立闭卷。
- A9 评测基础设施冻结；B0 `0ca4561` 只读。
- A2/B1/C2/D 未启动，等待研究负责人单独授权。
- A10–A12 仍未完成。

