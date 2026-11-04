# B0 M2a-FIX2 报告

```text
reviewed_commit:      cfff6168f4931ed7c1dd3907ad5ae9fe5ae7fe71
next_commit:          见本次提交
X05_negative:         pass（empty/blank/nested-empty Record 均被拒）
X05_positive_roundtrip: pass（嵌套 Record propose→close→reopen→inspect）
existing_tests:       65/65
independent_closed_book: not_run
```

## 修复

- `typeError` 增加空 Record、空白字段名检查，与 JSON 解码器一致。
- `propose` 在写事务前对签名类型做 canonical→decode 往返；不可逆 → `invalid_type`。
- 根因：`propose` 成功写入但 `inspect` 无法解析，破坏“已注册能力可重启恢复”的不变量。

## 测试

- `X05`：4 种非法 Record 类型全部拒绝，`capabilities` 保持 0 行。
- `X05b`：合法嵌套 Record 可写入、重开、inspect，类型一致。
- 全套：65/65（V1/V2 27 + M1 21 + M2a 17）。

## 未做

M2b 验证/激活门禁、M2c、M3、M4 均 `not_implemented`；独立闭卷 `not_run`。
