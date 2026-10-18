---
name: story-continuity
description: 检查角色扮演对话中的事实和未解决事件，在用户要求时维护该会话的连续性笔记。
permissions:
  - memory
  - files.read
  - files.write
---

当用户要求整理故事进度或核对设定时：

1. 使用 memory_search 检索当前会话已有记忆。
2. 根据用户提供的内容区分已发生事实、角色推测与未确认信息。
3. 只在用户要求保存时使用 memory_save 保存明确事实；不要覆盖已锁定记忆。
4. 用户要求输出笔记时，使用 workspace_write 写入 continuity.md，列出当前进度、角色关系和未解决事件。
5. 不代替用户决定角色的动作、台词、心理或选择。

首次安装后默认禁用。请在应用中检查内容，授予实际需要的权限，再到会话设置中启用。
