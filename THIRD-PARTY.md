# 第三方组件与许可证

本项目自身以 MIT 许可发布（见 `LICENSE`）。下面列出随发行版一起分发的第三方组件及其许可。

## 字体

| 项 | 内容 |
| --- | --- |
| 文件 | `NotoSansCJKsc-Regular.otf`（15.7 MB） |
| 位置 | `app/src/main/resources/fonts/`，构建后位于 jar 内的 `fonts/` |
| 家族名 | Noto Sans CJK SC |
| 版权 | © 2014-2021 Adobe (http://www.adobe.com/) |
| 许可 | **SIL Open Font License 1.1**（OFL-1.1） |
| 许可证全文 | `app/src/main/resources/fonts/OFL.txt`；同一份文本也随 jar 分发 |
| 来源 | <https://github.com/googlefonts/noto-cjk>（`Sans/OTF/SimplifiedChinese/`） |
| 是否修改 | **否**，原样分发，仅用于界面渲染 |

### 我们的使用方式与 OFL 的对应关系

OFL-1.1 允许**商用**、允许**随软件分发**、允许**嵌入**到产品中。我们履行了它要求的义务：

1. **随附许可证全文** —— `fonts/OFL.txt` 打进 jar，随每个发行包分发；
2. **不单独售卖字体** —— 本字体只作为启动器界面的一部分提供，不单独出售、不单独计费；
3. **未修改** —— 因此「保留字体名（Reserved Font Name）」条款不涉及；若将来要裁剪或改动字形，
   必须同样以 OFL 发布，并换一个不含 `Source` / `Noto` 的名字。

商标方面：「Noto」是 Google 的商标。这里只作**事实性来源说明**，不用于宣传或暗示背书。

## 运行时依赖

| 组件 | 许可 | 说明 |
| --- | --- | --- |
| JavaFX | GPL v2 + Classpath Exception | 由 jlink 打进自带运行时 |
| Jackson (databind / core / annotations) | Apache License 2.0 | JSON 解析 |
| SLF4J | MIT | 日志门面 |

这些依赖的完整许可文本随各自 jar 分发，位于 jar 内的 `META-INF/` 下。

## 第三方服务

启动器会访问以下外部服务，它们的**服务条款与隐私政策由各自提供方制定**，不属于本仓库的分发内容：

Microsoft 身份平台、Xbox Live / XSTS、Minecraft 服务（`api.minecraftservices.com`）、
Mojang 版本与资源元数据、Modrinth、CurseForge、GitHub（更新检查）。

详见 `site/privacy.html` 的「Network connections」一节。
