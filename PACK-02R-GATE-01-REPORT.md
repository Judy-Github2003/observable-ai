# PACK-02R-GATE-01 研究报告

**任务：** PACK-02R-GATE-01（Research Console 专项修复授权机制）  
**代码提交：** `d940775bf30e38a7cc15fe734eb27804326e3142`  
**测试：** Kotlin 315/315；Repair gate 7/7；旧 Console gate 5/5  
**状态：** GATE 已实现、验证并消费；AUTH 仍为 `PENDING_APPROVAL`  
**未实施：** AUTH、ACTIVE、EVAL、PACK-03

---

## 1. 根因与修改摘要

原 AUTH 被拒绝的原因：

- `PACK-02R-AUTH-01` 不在 `tasks.json`（194 个任务中无匹配）；
- 原 PACK-02 为 `PAUSED`，全局 `authorized_action=none`；
- PACK-02 普通补丁 `1/1` 已耗尽；
- 旧 `gate()` 只接受活动包内任务或普通 authorized_action，没有“专项修复”类型。

本次没有修改 `tasks.json`、没有新增研究任务、没有重置补丁额度。新增独立的 `research-console/repairs.json` 命名空间，以及只匹配 `allowed_task_id` 的专项门禁。

---

## 2. 实际修改文件

- `tools/research-console/server.py`
  - `load_repairs` / `save_repairs` / `validate_repairs`
  - `repair_digest`（不可变授权字段的 SHA-256）
  - `repair_gate`（ACTIVE、未消费、审计事件、父包/任务、哈希校验）
  - `consume_repair`（一次性消费 + 追加审计）
- `tools/research-console/console_cli.py`
  - `preflight` 优先检查专项授权命名空间；专项任务不经过普通 pack gate
  - 新增 `finish-repair`
- `tools/research-console/register_repairs.py`
  - 一次性登记 GATE 授权与 AUTH 待批准记录
- `tools/research-console/test_repair_gate.py`
  - 7 个独立测试
- `research-console/repairs.json`
  - 独立专项授权元数据
- `research-console/events.jsonl` / `history/`
  - 授权/注册审计事件

没有改动 `tasks.json`、C4/C8/H01 状态、WORKSTATE 内容或 E3 生产代码。

---

## 3. 专项授权的创建、验证与终止

### 记录字段

`repair_id`、`parent_pack_id`、`parent_task_ids`、`approved_scope`、`approval_reference`、`authorized_by`、`allowed_task_id`、`allowed_files_or_modules`、`authorization_status`、`created_at`、`consumed_at`、`audit_event_ids`、`content_hash`。

### 创建

- GATE-01 由本次研究负责人消息作为批准依据登记；`authorized_by=research_owner`，`approval_reference` 指向该消息。
- AUTH-01 仅登记为 `PENDING_APPROVAL`；没有冒充最终执行授权。
- 不提供“随便创建任意 ACTIVE 记录”的 CLI；登记脚本是一次性管理脚本，非通用后门。

### 验证

`repair_gate` 要求：

1. `allowed_task_id` 与请求任务完全匹配；
2. `authorization_status == ACTIVE`；
3. `consumed_at == null`；
4. `authorized_by == research_owner`，批准依据/范围非空；
5. `content_hash == repair_digest(record)`；
6. 父包存在，所有 parent task 存在；
7. `allowed_files_or_modules` 非空；
8. 至少一个 `audit_event_ids`，且历史事件文件实际存在。

任一失败即 `REFUSED`，不会静默通过。

### 终止

`finish-repair`：

- 再次执行 `repair_gate(for_finish=True)`；
- 设置 `authorization_status=CONSUMED`、`consumed_at`、commit/test/report；
- 追加 `repair_consumed` 审计事件；
- 该记录不能再通过 `preflight` 或再次消费。\n\nGATE 实际消费事件：`ev-1793894868948-1ccc45`；再次 preflight 返回 `REFUSED: repair authorization status CONSUMED`。

---

## 4. 能否阻止任意未授权任务

**是，在正常管理路径内。**

实测：

```text
preflight --task PACK-02R-GATE-01
AUTHORIZED_REPAIR: PACK-02R-GATE-01

preflight --task PACK-02R-AUTH-01
REFUSED: repair authorization status PENDING_APPROVAL

preflight --task C4
REFUSED: task has no pack authorization
```

专项授权不修改 `authorized_action`；即使手工把它改成 `PACK-02R-GATE-01`，普通 C4 仍被旧 gate 拒绝。专项授权不自动继承给 ACTIVE/EVAL/PACK-03；没有对应 `allowed_task_id` 记录即拒绝。

---

## 5. 原 PACK-02 状态是否不变

**是。**

- `active_pack=PACK-02/PAUSED`；
- `authorized_action=none`；
- C4/C8 `PAUSED`，RES-H01 未改（当前数据为 `REVIEW`，与复核摘要的 `ACCEPTED_LIMITED` 仍有状态不一致）；
- 普通补丁记录 `1/1` 未清零；
- `tasks.json` 无变更；
- 没有自动开始 AUTH。

---

## 6. 测试结果

### 新增专项门禁测试（7/7）

- 合法 GATE 授权仅允许其 `allowed_task_id`；
- 合法 AUTH fixture 可登记通过；ACTIVE 不继承；
- PENDING 拒绝；
- 未登记普通任务拒绝；
- 哈希不匹配拒绝；
- 缺审计事件拒绝；
- 普通 C4/C8/H01 仍拒绝；
- 修改 `authorized_action` 不能授权 C4；
- 消费一次后再次验证拒绝；
- `finish-repair` 消费状态与审计写入。

### 旧 Console gate（5/5）

PACK-01 独立门禁测试全部通过。

### 全量回归

Kotlin：**315/315**，B0 173 + E3 142，失败 0。  
没有 E3 生产代码修改；C4/C8 仍 PAUSED；独立闭卷 `not_run`。

---

## 7. AUTH 是否已具备登记和授权条件

- **已具备登记条件**：`repairs.json` 已有 `PACK-02R-AUTH-01` 记录，字段完整，允许范围限于：
  - `ExecutionFactAdapter.kt`
  - `Deduction.kt`
  - PACK-02 测试
- **尚不具备执行条件**：状态为 `PENDING_APPROVAL`；`preflight` 明确拒绝。
- 当前仍需要研究负责人执行最后的正式授权动作（将 AUTH 记录从 `PENDING_APPROVAL` 转为 `ACTIVE`，并产生对应审计事件/哈希），或提供等价的正式授权入口。

因此本轮只完成 GATE，不开始 AUTH。

---

## 8. 剩余风险与回滚

- 本次专项门禁是进程内本地管理边界，不是跨进程密码学身份系统；拥有仓库/数据库写权限的人仍可改数据。已明确不宣称抵抗管理员级攻击。
- 授权激活动作尚未提供受控 CLI，AUTH 的最终 ACTIVE 化需要研究负责人明确操作；这是下一步需要补齐的管理细节。
- 回滚：代码回退 `d940775`；`repairs.json` 可保留或删除，不会影响 tasks；审计事件保留。
- 未修改生产认知代码，无需回滚 E3。

## 9. 是否建议进入 PACK-02R-AUTH

**建议进入，但必须先由研究负责人把 AUTH 记录正式置为 ACTIVE。**

GATE 已能安全区分普通任务和专项修复；AUTH 的认证链漏洞仍未修复。下一步应只实施 AUTH，不应自动进入 ACTIVE/EVAL。

