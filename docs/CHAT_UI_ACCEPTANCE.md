# 0.1.3 聊天界面验证

聊天页采用暖白纸色与森林绿，深色模式对应深森林背景；助手内容保留通栏正文宽度，用户消息使用浅色气泡。会话和模型查询放在底部面板，设置与消息操作收进菜单。正文为 17sp / 28sp，遵循系统字体缩放。

## 原生渲染证据

使用 [Android 官方 Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing)，在 Windows 主机通过 Layoutlib 渲染真实生产 `PocketApp`，并人工查看全部 8 份图片：

| 场景 | 预览尺寸 / 条件 | 检查内容 |
| --- | --- | --- |
| 普通中文对话 | 420 × 860 dp | 气泡、正文、时间、消息操作、输入栏、底部导航 |
| 长文 | 420 × 980 dp | 标题、列表、代码、最后一段和输入栏分离 |
| 窄屏 | 320 × 720 dp | 正文换行、标题和模型省略、按钮布局 |
| 深色 | 420 × 860 dp | 文字对比、绿色气泡与表面色阶 |
| 大字体 | 420 × 980 dp / 200% | 控件不互相覆盖，输入提示保持单行，正文仍可滚动 |
| 空态 | 420 × 860 dp | 欢迎页与新建入口 |
| 生成中 | 420 × 860 dp | 思考折叠入口、生成状态与停止按钮 |
| 待发附件 | 420 × 860 dp | 文件标签、移除入口、发送按钮 |

初次检查发现 200% 字体下输入提示占用两行，已缩短提示并限制提示为单行；消息时间和菜单也已统一右对齐。最后重新渲染、检查修正画面并执行比较，结果 **8 项通过，0 失败**。

测试源码位于 `app/src/screenshotTest/kotlin/app/pockettavern/ui/ChatScreenshots.kt`，参考图位于 `app/src/screenshotTestDebug/reference/`；HTML 报告生成在 `app/build/reports/screenshotTest/preview/debug/index.html`。参考图只作为后续视觉回归基线，不打进 APK。

预览使用固定 ID、时间、测试文字和无网络的 `AppActions`，不包含真实聊天或用户设定。这些结果只证明所列状态的原生布局渲染，不能证明真机触摸、软键盘、无障碍滚动、流式网络或安装升级已验收。长文和大字号画面默认停在最新消息底部，因此顶部的历史内容可能部分滚出视口。

## 重现

```powershell
$env:JAVA_HOME='I:\jdk1.17'
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\gradlew.bat :app:updateDebugScreenshotTest -PandroidMirror=https://maven.aliyun.com/repository/google
# 查看并认可参考图后运行：
.\gradlew.bat :app:validateDebugScreenshotTest -PandroidMirror=https://maven.aliyun.com/repository/google
```

截图插件固定 `0.0.1-alpha16`；测试注解 API 固定 `0.0.1-alpha10`，以兼容项目 Kotlin 2.0.21。alpha16 的注解 API 引入 Kotlin 2.2 元数据，不能由本项目编译器读取。上述组合已完成实际渲染与比较；没有升级生产 Kotlin 或 Compose 版本。

## 待设备交互验收

- 发送失败保留草稿；成功才清空；附件可单独发送。
- 生成时可停止；向上阅读不被新片段拉回；向下箭头回到最新消息。
- 切换会话或底部栏目后草稿和阅读位置保留。
- 键盘打开时底部导航隐藏，输入栏和发送按钮不被遮挡。
- 模型筛选、预设、历史会话、菜单、复制、编辑、分支、摘要回退、Skills 均可完成操作。

