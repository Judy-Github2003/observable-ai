# Observable AI — DMRV-01 单包科研任务

**D+M+R+V：学习策略 × 表征结构选择 × 元认知 × 科学评测。**

## 使用

此包**同时包含公开学生参考代码、公共契约测试、可见考官和审计工具**，不需要分 A/B 两次发送。整个 ZIP 直接交给 DeepSeek。

1. 解压后 `python tools/verify_package.py`。
2. 阅读 `docs/01-RESEARCH-PROTOCOL.md` 和 `docs/02-DEEPSEEK-EXECUTION.md`。
3. 在原 Observable AI Git 仓库中执行 `python tools/install_into_repo.py <repo路径>`，确认不会覆盖已有文件。
4. 执行 DMRV 公开 Kotlin 契约测试，目标 14/14。该步骤与正式考试不同。
5. 通过 Research Console 为独立 DMRV-01 研究包登记授权和 preflight；不得挂 PACK-02。
6. 运行 `python tools/dmrv/freeze_dmrv.py --repo .`，提交冻结源码与清单。
7. **正式考试只运行一次**：`python tools/dmrv/run_dmrv.py --repo . --official --output build/dmrv-01-official-once`。
8. `python tools/dmrv/audit_dmrv.py build/dmrv-01-official-once`。
9. 提交科研结果 ZIP 与 Git 记录；停止，不启动 DMRV-02。

如果 Windows 无 `kotlinc`，可用已有 Kotlin embeddable 编译器等价替换运行入口，但不得改算法或冻结文件，并保留全过程。禁止无筛选全量历史回归。

## 内容结构

- `src/main/kotlin/logic/e3/dmrv/DMRV01Kernel.kt`：可审计的上下文奖励学习器、预定义结构候选与元策略。
- `src/test/kotlin/logic/e3/dmrv/DMRV01PublicContract.kt`：14 项公开行为测试。
- `examiner/DMRV01Examiner.kt`：元训练 / 元测试、匹配对照、先冻结后评分、开发模式与正式模式隔离。
- `tools/install_into_repo.py`：不覆盖已有源码的安装工具。
- `tools/freeze_dmrv.py`：一次性冻结学生/考官/公开契约。
- `tools/run_dmrv.py`：独立 Kotlin runner，一次性官方执行保护。
- `tools/audit_dmrv.py`：Python 重算策略学习、模型行为、评分、成对结果。
- `docs/`：协议、DeepSeek 执行指令、科学限制及结果报告模板。
- `SHA256SUMS.txt`：所有文件哈希。

## 与既有项目的区别

前面 PACK-02R 研究知识复用，GNJV-01 研究因果干预，BMNV-01 研究新信息与复合概念。DMRV-01 **不继续增加算法版本**，只研究一个新的机制：**利用过去任务的反馈，学习下一次选择哪种表征搜索策略**。

完整策略和目标算子均由开发者提供；本轮不声称学生自主发明学习算法、操作类型或开放认知系统。

包中可见考官，即使冻结与重算均 PASS，也属于 `IMPLEMENTER_RUN / VISIBLE_EXAMINER`，绝不能写成 `INDEPENDENT_CLOSED_BOOK`。
