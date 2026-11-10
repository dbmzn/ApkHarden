# WorkKit 应用图标

采用已选定的蓝绿交错扁平图形，白色圆角底板外为透明区域。

- `workkit-master.png`：图标母版；保留透明边缘。
- `workkit.icns`：macOS 打包图标，包含 16–1024 像素表示。
- `workkit.ico`：Windows 打包图标，包含 16/24/32/48/64/128/256 像素表示。
- `../../src/main/resources/icons/workkit.png`：512 像素运行窗口、Dock 和 Linux 图标。

应用侧栏使用 `ToolIcons.WorkKit` 中的交错线性标识，采用当前主题主色，与导航图标
保持相同线宽和圆角；不直接展示带底板的彩色应用图标。

资源随项目提供，正常构建不需要额外图片工具。在 macOS 上更换母版后运行：

```sh
python3 scripts/prepare-app-icons.py
./gradlew createDistributable
```

导出脚本使用 Python 标准库、Swift/AppKit（需 Xcode 命令行工具）及 macOS 的
`sips`、`iconutil`；先统一裁切平滑圆角边缘，再生成各尺寸。
母版由内置 ImageGen 根据选定预览图处理；最终编辑要求是保留蓝绿交错图形、比例、
白色底板和扁平风格，仅将底板外的灰色背景及投影替换为透明区域。
