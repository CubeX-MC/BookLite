# BookLite 手动回归记录

单元测试覆盖了序列化、存储和校验逻辑，但无法模拟 Bukkit 事件、讲台、容器
和卸载模式。下表是发布级手动回归清单，需要在真实 Paper / Spigot 服务器上
执行并填写结果。

## 执行环境

| 项目 | 值 |
| --- | --- |
| 服务端 | _待填写，如 Paper 1.20.x_ |
| BookLite 版本 | 0.1.0 |
| 构建产物 | `target/booklite-0.1.0.jar` |
| 执行人 / 日期 | _待填写；自动化复查：Codex / 2026-05-24_ |

## 回归场景

状态取值：`通过` / `失败` / `待执行`。

| # | 场景 | 操作 | 预期 | 状态 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 1 | 签书自动转换 | 写并签名一本成书 | 物品变为空壳书，聊天提示 `book.signed_converted`；物品可见标题和作者仍是原书信息 | 待执行 | |
| 2 | 右键阅读 | 右键空气持有空壳书 | 从 BookLite 后端打开数据库完整内容，不显示占位页 | 待执行 | |
| 3 | 普通方块不误触 | 持空壳书右键箱子 | 箱子正常打开，不打开虚拟书 | 待执行 | 边界修复验证 |
| 4 | 空讲台放入 | 持空壳书右键空讲台 | 空壳书被放入讲台，不打开虚拟书 | 待执行 | 边界修复验证 |
| 5 | 讲台阅读 | 右键已放入空壳书的讲台 | 打开数据库完整内容 | 待执行 | 边界修复验证 |
| 6 | 工作台复制 | 空壳书 + 空白书合成 | 产出复制代数 +1 的空壳书，copy-of-copy 被限制 | 待执行 | |
| 7 | `/booklite convert` | 手持原版成书执行 | 转换为空壳书 | 待执行 | |
| 8 | `/booklite restore` | 手持空壳书执行 | 还原为原版成书 | 待执行 | |
| 9 | `/booklite delete` + 阅读 | 删除后右键空壳书 | 显示 `book.deleted_page`，不泄露原文 | 待执行 | |
| 10 | `/booklite undelete` | 撤销删除后阅读 | 恢复显示原文 | 待执行 | |
| 11 | `/booklite purge <天数> confirm` | 清理软删除记录 | 记录被物理删除，计数正确 | 待执行 | |
| 12 | 短 ID 歧义和补全 | 用会命中多本书的短 ID 执行 `info`，并在 `info/read/delete/undelete` 第二参数按 Tab | 歧义输入提示 `commands.ambiguous_id`；Tab 补全返回完整 ID，`delete` 只补可用书，`undelete` 只补已删除书 | 待执行 | |
| 13 | 缺失数据降级 | 阅读指向不存在记录的空壳书 | 显示 `book.missing_page` | 待执行 | |
| 14 | 本地化 | 切换 `language: en_US` 重载后阅读缺失/已删除书 | 缺失/已删除提示书和聊天消息显示英文 | 待执行 | |
| 15 | 卸载模式 - 背包 | 开启卸载模式，玩家上线 | 背包内空壳书被动还原为原版书 | 待执行 | |
| 16 | 卸载模式 - 容器 | 看向 6 格内的容器方块执行 `/booklite restorecontainer` | 目标容器内空壳书被还原 | 待执行 | |
| 17 | reload | 修改语言后 `/booklite reload` | 配置与语言即时生效 | 待执行 | |

## 验证命令证据

按 `docs/agent-verification-matrix.md` 的格式记录：

```text
mvn verify
Result: passed on 2026-05-24. 27 tests, 0 failures, 0 errors, 0 skipped.
Notes: Built target/booklite-0.1.0.jar; includes ID tab completion storage filtering tests.
```

本地自动化无法代替上表中的真实服务器操作，尤其是 Bukkit 事件、讲台交互、
客户端开书 GUI 和卸载模式容器恢复。发布前仍需在实际 Paper/Spigot 服务器上
把场景 1-17 的状态从“待执行”更新为“通过”或“失败”。
