# E3-A2 工作报告：最小独立世界生成器

```text
阶段: E3-A
范围: A1 审查修正 + A2（SOLVABLE / LATENT_ALIASING）
状态: complete
A1 修正提交: f826ca51575c8a683cb0cf1a99bea469193220b3
A2 代码提交: 14567ac16d42ab05157ad65ad05fb15a5ecfface
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 192/192（B0 173 + E3 19），失败 0
独立闭卷: not_run
```

## 1. A1 审查修正

### FeatureKey 编码碰撞

- 旧：`Known("?")` 与 `Unknown` 都编码为 `field=?`。
- 新：`Known(v)` → `K:<escaped(v)>`；`Unknown` → `U`；字段名/值转义 `\ | =`。
- 新增测试覆盖 `?`、`\`、`|`、`=`、`U`、`K:?`、空串及组合值，断言已知值之间、已知与未知均不碰撞。

### 重复观测语义

- 唯一键从 `(objectId, round)` 改为 `(objectId, round, sourceId)`。
- 不同来源同一对象同一轮：允许（独立重复测量）。
- 同一来源同一对象同一轮：拒绝，错误码 `duplicate_source_round`。
- 限制说明：这只能防“同一 sourceId 自报重复”；来源是否真独立需评测端另行审查。

## 2. A2 生成器接口

文件：`src/main/kotlin/logic/e3/evaluator/E3WorldGenerator.kt`

```text
WorldFamily = SOLVABLE | LATENT_ALIASING
E3WorldGenerator(seed).generate(family, index) -> EvaluatedWorld
EvaluatedWorld.public : PublicWorld  // 学生可用
EvaluatedWorld.truth  : HiddenTruth  // 评测端
E3WorldOracle(truth).label(sampleId) -> Boolean
```

- `PublicWorld`：`worldId`、`VisibleSchema`、带标签公开观察、公开查询池；不含 family/hidden feature/rule。
- `HiddenTruth`：family、规则描述、隐藏特征、sampleId→label、有限域全枚举；只在 evaluator 包。
- 公开 `worldId` 使用 `world-<seed>-<index>-<hex>`，不含 `solvable`/`latent` 字样。

## 3. 两个世界家族

### SOLVABLE

- 字段：`material={iron,copper,ceramic}`、`seal={sealed,open,partial}`。
- 规则模板（由 seed 选）：
  1. `material == copper`
  2. `material == copper AND seal == sealed`
  3. `seal == sealed OR material == ceramic`
- 公开 5 条（保证至少一真一假），查询池 2，隐藏 2；隐藏输入均为未见可见向量。

### LATENT_ALIASING

- 可见：`kind={k0,k1,k2}`、`shape={s0,s1}`；隐藏 `latent∈{0,1}`。
- 规则：`kind == k1 XOR latent`。
- 公开含同一可见向量、不同对象/来源、不同标签的碰撞对；隐藏 2 个未见可见向量。
- 边界：这只证明**当前可见属性不能唯一决定标签**；不证明学生能发现隐藏属性，也不证明真实世界存在该属性。

## 4. 自检（实际运行）

`E3WorldGeneratorTest` 8 项 + `E3WorldSampleExportTest` 1 项：

- 同 seed 两家族完全复现；
- 不同 seed（1..5）公开数据或真值至少变化一次；
- 公开编码不含 `latent`/`worldKind`/`targetAst`；
- oracle 对 `fullDomain` 每个 sampleId 与真值一致；
- LATENT_ALIASING 公开数据存在同可见向量不同标签；
- 公开观察通过 `validateObservations`；
- 隐藏点与公开可见向量不相交；查询池与隐藏不相交；
- FeatureKey 对字面 `?` 与 Unknown 仍可区分。

命令：

```powershell
$env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'
.\gradlew.bat --offline --no-daemon test
```

结果：**192 passed / 192 total，0 failed**。

## 5. 示例数据

`reports/a2-sample-worlds.json`（seed=42）：

- 含两家族的 public（schema/observations/queryPool）与 evaluator truth（rule/hiddenFeature/labels/fullDomain）。
- 该文件是评测端复核材料，**不可交给学生接口**；学生只应拿到 `PublicWorld`。

## 6. 未做

- 未实现 A3 的 `NOISY_LABELS` / `SEARCH_LIMITED` / `AMBIGUOUS_EVIDENCE`。
- 未实现诊断算法、探测策略、评分器或 B0 调用。
- 未修改 B0 冻结源码；E3-B/C 未授权。
- 独立闭卷 `not_run`；本报告是 `implementer_run`。

## 7. 下一步

`A3`：增补 `NOISY_LABELS`、`SEARCH_LIMITED`、`AMBIGUOUS_EVIDENCE` 世界族；完成后停止等待“继续”。
