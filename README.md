# Misaka Launcher

**简体中文** | [English](README.en.md)

跨平台（Windows 10/11、Linux、macOS）的 Minecraft 启动器。自带 Java 运行时管理：按版本自动挑选、下载并安装到启动器同级目录，目标机器无需预装任何环境。

> **本项目是非官方第三方启动器**，与 Mojang Studios、Microsoft 均无关联，未获其认可或赞助。
> “Minecraft” 是 Mojang Studios 的商标，本项目仅在描述兼容性时使用。

---

## 功能一览

| 模块 | 能力 |
| --- | --- |
| 版本与实例 | 多实例隔离，每个实例独立版本、模组、资源包、存档与启动参数 |
| 版本安装 | 原版安装；默认走**官方源**，官方不可达时才降级镜像；也可在设置里指定 BMCLAPI 镜像或自建源 |
| 模组加载器 | Forge、NeoForge、Fabric、Quilt、OptiFine |
| Java 运行时 | 按 Minecraft 版本自动判定主版本，下载并安装到启动器同级目录 |
| 账户 | **仅微软正版登录**（浏览器回环回调 + 设备码兜底）；不支持离线账户与任何第三方认证服务器 |
| 下载中心 | Modrinth / CurseForge 检索与安装，支持热门排序、分类、分页、缩略图 |
| 模组与资源包 | 模组更新检查（CurseForge / Modrinth / 官方直链三条来源）、资源包启停与拖拽排序 |
| 整合包 | 本地 `.zip` / `.mrpack` 导入安装；从检索结果直接安装 |
| 启动前同步 | 复刻 FileSync 协议（`/api/file_list`、`/api/config`、`/api/file/<path>`），软/硬同步、重定向、MD5 校验 |
| 贴图兼容 | AMD / Intel 显卡图集上限较低时，自动生成覆盖包避免贴图拼接崩溃 |
| 自更新 | 对接自建 ZooKeeper 更新服务，按平台挑包、分块校验后原地替换 |
| 界面 | 左侧分组导航 + 常驻启动卡片；玻璃拟态、动态背景 |

---

## 需要你自己填的两处（重要）

本仓库**不内置任何凭据**。这两项在源码里是空串，`fork` 后由**构建期注入**，不需要改源码：

| 凭据 | 注入用的名字 | 不注入的后果 |
| --- | --- | --- |
| 微软 OAuth 应用（public client） | `MISAKA_MS_CLIENT_ID` | 正版登录不可用；账户页提示“本构建未内置微软应用 ID” |
| CurseForge 目录 API Key | `MISAKA_CURSEFORGE_API_KEY` | 该来源显示为“未配置”，下载中心自动回退到 Modrinth |

取值方式（优先级从高到低）：

```powershell
# 1) 环境变量（CI 用 GitHub Secrets 注入同名变量）
$env:MISAKA_MS_CLIENT_ID = '...'
$env:MISAKA_CURSEFORGE_API_KEY = '...'

# 2) Gradle 属性（写 ~/.gradle/gradle.properties，不要写仓库里那份）
#    misaka.microsoftClientId=...
#    misaka.curseforgeApiKey=...
```

`core:generateBuildSecrets` 会把值生成成 `BuildSecrets` 的**编译期常量**（生成文件在 `core/build/generated/` 下，不入库）。CI 从 GitHub Secrets 读同名环境变量，本地则用上面的环境变量或用户级 `gradle.properties`。

这两个值**刻意不提供运行时覆盖**：它们代表“谁在调用 API”，允许运行期修改就等于允许任何人冒用他人的应用登记。

### 微软 Client ID 的获取与审核

1. 在 Azure 门户注册应用，账户类型选「仅个人 Microsoft 账户」；
2. 平台选「公共客户端 / 移动和桌面」，重定向地址填 `http://localhost`；
3. 「身份验证 → 允许公共客户端流」选「是」；
4. **必须**按 <https://aka.ms/AppRegInfo> 提交 Mojang 审核，通过后才允许换 Minecraft 令牌（否则报 `Invalid app registration`）。

申请材料的要点：可访问的官网、隐私政策、条款页、清晰的功能描述、可下载的发行版本、开源仓库地址。被拒没有申诉渠道，只能改进材料后重新提交。

### CurseForge Key

到 <https://console.curseforge.com> 申请（形如 `$2a$10$…`）。该 Key 仅用于读取公开的模组元数据，请勿用于写入类操作。

---

## 系统要求

- **运行**：Windows 10/11 x64、主流 Linux x64、macOS 11+（Intel / Apple Silicon）；无需预装 Java
- **构建**：JDK 17（需同时提供 `javac` 与 `jmods`，用于 jlink/jpackage）

## 构建

```powershell
# Windows
.\build_all.ps1                 # 编译 + 测试 + 打包
.\build_all.ps1 -SkipTests      # 跳过测试
.\build_all.ps1 -Installer      # 额外生成安装包（需 WiX Toolset）
```

```bash
# Linux / macOS
./build_all.sh
```

产物在 `dist/`：

| 文件 | 说明 |
| --- | --- |
| `MisakaLauncher-windows-x64.exe` | **站点下载按钮用的固定名**（单文件自解压包） |
| `MisakaLauncher-linux-x64.jar` / `-macos-arm64.jar` / `-macos-x64.jar` | 同上，交叉编译的 jar |
| `MisakaLauncher-<版本>-windows-portable.exe` | 带版本号的原件（存档 / 自更新服务） |
| `MisakaLauncher-<版本>-<平台>-<架构>.jar` + `.sha256` | 交叉编译的可执行 jar，目标机需 Java 17 |
| `MisakaLauncher-<版本>-windows-portable.zip` | 免安装目录归档 |

> 固定名与带版本号的文件内容完全一致，由构建同时产出；固定名让网站链接不必随版本变化，详见 `docs/RELEASING.md`。

> 产物名与更新服务的产品键都由 `app/build.gradle.kts` 里的 `appId`（`MisakaLauncher`）派生，改一处就全部生效。
> 历史标识 `MisakaMinecraftLauncher` 已停用（产品名里不再含 Minecraft 商标）；由于产品键随之变更，老客户端只认旧键，
> 迁移步骤（新旧两个键并行的顺序）见 `docs/ZooKeeper接入说明.md`。

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/SELF-HOSTING.md`](docs/SELF-HOSTING.md) | **自建服务端指南**：更新服务需要实现的 3 个接口、模组同步需要实现的 4 个接口，含请求/响应示例与最小实现 |
| [`docs/ZooKeeper接入说明.md`](docs/ZooKeeper接入说明.md) | 与自建 ZooKeeper 更新服务对接的完整细节（平台映射、发版流程、排查表） |
| [`使用说明.md`](使用说明.md) | 面向使用者的操作说明 |

---

## 项目结构

```
app/        JavaFX 界面、启动流水线、任务中心、页面装配
core/       与 UI 无关的核心逻辑：安装、下载、认证、模组、同步、更新、配置、i18n
packaging/  jpackage / 自解压等打包资源
docs/       对外文档
```

- 界面层只依赖 `AppShell` 接口，核心层不引用任何 JavaFX 类型；
- 长耗时操作统一走 `TaskCenter`（可取消、可并发、进度聚合）。

## 参与贡献

1. Fork 并创建分支；
2. `./gradlew :core:test` 必须全绿（核心逻辑有较完整的单测）；
3. 提交 PR 时说明动机与影响面，涉及界面的附截图。

**请勿在 PR 中提交任何真实 API Key、Client ID、账号令牌或个人路径。**

## 许可证

[MIT](LICENSE)

随发行版分发的第三方组件及其许可（内置中日韩字体为 **SIL OFL 1.1**）见 [THIRD-PARTY.md](THIRD-PARTY.md)。
