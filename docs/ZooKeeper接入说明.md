# ZooKeeper 对接说明（开发文档）

本文档只讲一件事：本启动器如何与自建的 **ZooKeeper** 更新服务对接。
面向「改更新相关代码」和「发新版本」的人；服务端接口的完整定义见 ZooKeeper 仓库的 `docs/api.md`。

---

## 1. 对接身份（固定值）

| 项 | 值 | 定义位置 |
|---|---|---|
| **产品标识**（product key） | `MisakaLauncher` | `core` → `ZooKeeperUpdateClient.PRODUCT` |
| **产品显示名称**（name） | `Misaka Launcher` | `core` → `ZooKeeperUpdateClient.DISPLAY_NAME` |
| 发布通道（channel） | `stable` | 配置项 `update.channel`，默认即为 `stable`，设置页可切换 |
| 产品模式（mode） | `managed` | 服务端登记项；不是 `managed` 时上传接口会拒绝 |
| 服务地址 | 默认 `http://127.0.0.1:8080` | 配置项 `update.serverUrl`，设置页可改 |
| 鉴权 | 可选，请求头 `X-API-Key: <密钥>` | 配置项 `update.apiKey` |

**产品标识与显示名称硬编码在代码里，刻意不做成配置项。** 原因：产品键填错的后果是
「检查更新永远返回产品不存在」，而界面上看不出到底是键填错了还是服务端没登记；
写死之后，发布侧与客户端侧都只剩一处需要确认。服务地址与密钥仍然可配置（不同客户机可能连不同的服务）。

- 显示名称用在更新对话框标题（`Misaka Launcher · 发现新版本`）与日志里。
- 有回归测试钉住这两个值：`ZooKeeperUpdateClientTest.产品标识与显示名称必须与 ZooKeeper 登记的一致`。
  改动常量会让测试变红——这是故意的，提醒你**服务端登记也要同步改**。
- 检查请求发出后，服务端回显的 `product` 会与本地常量比对，不一致会打 `WARN`
  （防止 ZooKeeper 上登记了大小写不同的同名产品，那会一路正常直到换机器才发现装的是别的产品）。

### 1.1 产品键改名的迁移顺序（一次性）

产品标识曾为 `MisakaMinecraftLauncher`，因产品名去商标而改为 `MisakaLauncher`。
键一变，**老客户端就再也收不到新包**（它们只会拿旧键去问），所以服务端要按顺序做：

1. 在 ZooKeeper 上登记新产品 `MisakaLauncher`（`managed` 模式，通道 `stable`），
   把新版上传/同步到它下面；下载归属账号也建一个同名的（`X-ZK-User` 发的是新名）；
2. 旧产品 `MisakaMinecraftLauncher` **先别删**：把同一份新版也挂到旧键下，
   仍在跑旧版本的客户端靠这一条才升得上来（升到新版后它自然改用新键）；
3. 现场客户端全部升级完，再停用旧键。

判断升级是否完成的依据：服务端访问日志里不再出现 `MisakaMinecraftLauncher` 的 `/api/v1/updates/check`。

---

## 2. 操作系统与架构的对应关系

客户端**每次检查都会带上** `platform` 与 `arch`，服务端据此挑选适用于该操作系统的包。
映射由 `ZooKeeperUpdateClient.currentPlatform()` / `currentArch()` 推导：

| 客户端实际系统 | 发出的 `platform` | 备注 |
|---|---|---|
| Windows | `windows` | `OsUtils.current()` → `WINDOWS` |
| Linux | `linux` | |
| macOS | `darwin` | **不是 `macos`**，这是最容易写错的一个 |
| 识别不出 | `any` | 兜底 |

| 客户端 JVM 架构 | 发出的 `arch` |
|---|---|
| x86_64 / amd64 | `x64` |
| x86 | `x86` |
| aarch64 | `arm64` |
| arm | `arm` |
| 其它 | `any` |

> ⚠️ **产物文件名里的 `amd64` 不是 ZooKeeper 的取值。**
> `MisakaLauncher-1.0.0-windows-amd64.jar` 里的 `amd64` 是本项目交叉编译的产物命名，
> 注册到 ZooKeeper 的 `arch` 字段必须写 `x64`（服务端枚举只有 `x64/x86/arm64/arm/any`，写其它值直接 400）。

### 2.1 服务端会做平台回落，所以客户端必须再挑一次

ZooKeeper 的「最新版本」命中规则（`docs/api.md` §5.2）：

```
(platform, arch) → (platform, any) → (any, any) → 该通道最新版本（兜底）
```

也就是说**拿回来的清单里，文件未必真的适配本机**（只发布过 `windows/any` 的产品，在被请求
`linux/arm64` 时仍会返回那份清单）。因此客户端用 `ZooKeeperUpdateClient.selectFile` 再挑一次，顺序为：

1. 形态一致（后缀匹配当前运行形态）**且**平台/架构精确匹配；
2. 形态一致、但平台/架构靠 `any` 兼容；
3. 平台/架构精确匹配；
4. `any` 兼容。

**明确写着别的平台/架构的文件一律不要**，哪怕整个版本只有这一个文件：此时宁可报
「该版本没有适用于 windows/x64 的更新包」（日志里会同时写明清单里有几个文件），
也不能把别的系统的包装上去——替换前的形态校验拦得住 `.exe` / `.sh`，**拦不住跨平台的 `.jar`**
（Linux 的 jar 在 Windows 上照样能覆盖自身，损坏是不可逆的）。

### 2.2 客户端实际会下载哪一个

「形态」指启动器自己是怎么跑起来的（`SelfUpdateInstaller.locate()`：优先看单文件外壳写入的
`MISAKA_SELF_EXE`，拿不到时按代码所在位置推断）。因此同一台 Windows 上，单文件 exe 形态更新的是那个
exe 本身，jar 形态（含免安装目录）更新的是 `app/` 里的 jar —— **每种客户机实际下载哪个包，与
[§5.2 的登记对照表](#52-在-zookeeper-上登记)是同一份映射关系**，那里是按「要往服务端填什么」列的。

---

## 3. 客户端用到的接口

| 用途 | 接口 | 实现位置 |
|---|---|---|
| 检查更新 | `POST /api/v1/updates/check` | `ZooKeeperUpdateClient.check` |
| 更新说明 | `GET /api/v1/changelog/{product}?version=` | `fetchNotes`（取 `items[0].notes`） |
| 下载整包 | `GET /api/v1/download/{product}/{version}/{file}` | `download` |
| 分片索引 / 单片 | `GET .../{file}/chunks`、`.../chunks/{index}` | `download`（并发下载 + 逐片校验） |

检查请求的 body 是**数组**（服务端也接受 `{"items":[...]}` 形式）：

```json
[{"product":"MisakaLauncher","version":"1.0.0.0","channel":"stable",
  "platform":"windows","arch":"x64"}]
```

- `version` 取自 `AppInfo.version()`（即 `gradle.properties` 的 `misaka.version`）；
  服务端会把它归一化成 4 段（`1.0.0` → `1.0.0.0`）并**按段做数值比较**，所以 `1.10.0 > 1.9.0`。
- 下载整包时优先走分片端点：每片独立校验 SHA-256，最后再校验整包 SHA-256 与清单一致；
  `Range` / `If-Range` / `ETag` 用于断点续传，服务端文件被替换时回退为完整下载。
- 状态码映射：`401/403` → 密钥无效，`404` → 产品/版本不存在，`429` → 请求过于频繁。
- 服务端可能把「产品不存在 / 已停用 / 该通道没有可用版本」放在 **200 的 body** 里（`error` 字段），
  客户端会识别并当成失败返回。

---

## 4. 下载与替换（按系统形态）

替换对象与重启方式由 `SelfUpdateInstaller.locate()` 探出，再按形态执行：

| 当前运行形态 | 替换对象 | 重启方式 |
|---|---|---|
| Windows 单文件 `.exe` | 正在运行的 exe 自身（**先改名再落新文件**：Windows 不允许覆盖运行中的镜像，但允许改名） | 直接启动新 exe，它会自解压到新版本目录，数据根目录不变 |
| Linux / macOS 单文件 `.sh` | 脚本自身（Unix 允许直接覆盖运行中的文件） | 补可执行位后启动；**不补位会 `Permission denied`** |
| 可执行 `.jar` / 应用镜像里的 app jar | 当前 jar（先改名再落新文件；应用镜像另有外层启动器时优先用它重启） | `javaw -jar`（Windows，避免弹黑框）/ `java -jar`；有外层启动器时直接启动它 |

配套的两道守卫：

- **形态必须一致**（`requireSameKind`）：把 jar 复制成 exe（或反过来）不会报任何错，但用户手里
  会立刻多出一个「双击没反应」的启动器——这种损坏不可逆，因此替换前先校验后缀。
  也正因如此，**挑包时就把运行形态算进去了**（`selectFile` 的形态优先），否则要等下载完整个包才报错。
- **有游戏正在运行就拒绝更新**：更新完成后要退出启动器，而退出路径会结束游戏进程，
  不能拿玩家正在进行的游戏换一次更新。
- 替换产生的 `.old` 备份由**下一次启动**清理（旧进程退出前文件还被占用，当时删不掉）。

---

## 5. 发版流程

### 5.1 改版本号并构建

1. 改根目录 `gradle.properties` 的 `misaka.version`（版本号的唯一来源）；同时把
   `使用说明.md` 的「适用版本」手动同步（`.\build_all.ps1` 里的 `:app:verifyVersion` 会拦住漏改）。
2. 执行 `.\build_all.ps1`，产物落在 `dist/`。

### 5.2 在 ZooKeeper 上登记

产品（首次对接时建一次，之后只需确认）：

| 字段 | 值 |
|---|---|
| `key` | `MisakaLauncher` |
| `name` | `Misaka Launcher` |
| `mode` | `managed` |
| `default_channel` | `stable` |

版本：**表里每一行都是一条版本记录**（服务端唯一键是 `产品 + 版本 + 平台 + 架构`），
同一条记录下可以挂多个文件 —— 所以 Windows 的 exe 与 jar 是**同一条记录**下的两个文件。

| 客户机系统 | 客户端运行形态 | 上传哪个文件（`dist/` 内） | `platform` | `arch` | 文件名后缀必须是 |
|---|---|---|---|---|---|
| Windows | 单文件 exe | `MisakaLauncher-<版本>-windows-portable.exe` | `windows` | `x64` | `.exe` |
| Windows | 可执行 jar / 免安装目录 | `MisakaLauncher-<版本>-windows-amd64.jar` | `windows` | `x64` | `.jar` |
| Linux | 单文件脚本 | `MisakaLauncher-<版本>-linux-portable.sh` | `linux` | `x64` | `.sh` |
| Linux | 可执行 jar / 免安装目录 | `MisakaLauncher-<版本>-linux-amd64.jar` | `linux` | `x64` | `.jar` |
| macOS（Intel） | 可执行 jar | `MisakaLauncher-<版本>-macos-amd64.jar` | `darwin` | `x64` | `.jar` |
| macOS（Apple Silicon） | 可执行 jar | `MisakaLauncher-<版本>-macos-aarch64.jar` | `darwin` | `arm64` | `.jar` |
| macOS（任一架构） | 单文件脚本（须在 mac 上构建） | `MisakaLauncher-<版本>-macos-portable.sh` | `darwin` | `x64` / `arm64` | `.sh` |

**填值要点：**

- 平台上 macOS 是 **`darwin`**，不是 `macos`（服务端枚举只有 `windows` / `linux` / `darwin` / `android` / `any`）；
- 架构只有 `x64` / `x86` / `arm64` / `arm` / `any` 五个合法值：**产物名里的 `amd64` → 填 `x64`，
  `aarch64` → 填 `arm64`**；
- 平台 + 架构相同的多个形态（如上表 Windows 两行）**挂在同一条记录下**，客户端按自己的运行形态挑后缀；
- macOS 两个架构都要登记，缺哪个架构，那部分 mac 客户机就永远收不到更新；
- 三个平台的 jar 都能在 Windows 上由 `:app:distJarAll` 交叉编译产出；Linux / macOS 的单文件 `.sh`
  内含 jlink 运行时，只能在对应平台上构建。

其它字段（每条版本记录都要填）：

| 字段 | 填什么 |
|---|---|
| `version` | 与 `gradle.properties` 的 `misaka.version` 一致（服务端会归一化成 4 段） |
| `channel` | `stable` |
| `sha256` | 取 `dist/<同名文件>.sha256`，或 `SHA256SUMS.txt` 里的对应行 |
| `notes` | 更新说明；留空时客户端从 `notes_url`（changelog 接口）读取 `items[0].notes` |
| 发布状态 | 上传后**必须发布（publish）**：`draft` / `yanked` 的版本不会出现在检查结果里 |

### 5.3 自测

```bash
# 检查更新（数组形式，字段与客户端发出的完全一致）
curl -X POST http://127.0.0.1:8080/api/v1/updates/check \
  -H 'Content-Type: application/json' \
  -d '[{"product":"MisakaLauncher","version":"1.0.0","channel":"stable","platform":"windows","arch":"x64"}]'

# 看清单里到底挂了哪些文件（挑包依据）
curl "http://127.0.0.1:8080/api/v1/products/MisakaLauncher/latest?channel=stable&platform=windows&arch=x64"
```

启动器侧：设置 → 通用 → 「立即检查更新」；启动时也会在后台检查一次（可在设置里关掉）。
日志关键字：`检查 Misaka Launcher（MisakaLauncher）更新`、`发现新版本`、
`清单里没有适用于`、`服务端回显的产品标识`。

---

## 6. 排查对照表

| 现象 | 多半原因 | 动作 |
|---|---|---|
| 「产品不存在 / 已停用」 | 产品键大小写不一致、`mode` 不是 `managed`、产品被停用 | 核对 §1 表里的键，逐字比对 |
| 一直提示已是最新 | 版本号没升、通道不对、只发布了别的平台 | 用 §5.3 的 curl 直接问服务端 |
| 「该版本没有适用于 windows/x64 的更新包」 | 该版本没为这个平台/架构发布过 | 看清单里的 `files[].platform/arch`，补发对应平台 |
| 「更新包形态与运行方式不符」 | 发布时挂错了形态（例如给 exe 装 jar） | 按 §5.2 对照表补齐同形态的包 |
| 「有游戏正在运行」 | 设计如此 | 退出游戏后再更新 |
| 「当前运行方式不支持自动更新」 | 从 IDE 里跑（没有可替换的文件） | 用 `dist/` 或 `build_windows/` 里的产物测试 |
| macOS 上永远收不到更新 | `platform` 写成了 `macos` | 服务端枚举是 `darwin` |
| 架构不匹配 | `arch` 写了 `amd64` | 服务端枚举是 `x64` |

---

## 7. 安全现状与已知缺口（务必知情）

- **客户端目前不验签。** 服务端清单带 Ed25519 签名（`sig`），但客户端只校验 SHA-256，
  这保证**完整性**（下载损坏/被截断会失败）而**不保证来源**。真正的防线是：
  服务地址由使用者自己配置（默认指向本机自建服务）、更新包形态校验、以及下载后替换前的守卫。
- 因此**不要把更新服务暴露在不可信网络上用 HTTP**：能改这个地址或能劫持明文流量的人，
  就能让启动器把任意 exe 覆盖到自身。需要收紧时按 ZooKeeper `docs/api.md` §6 实现验签
  （公钥取自 `GET /api/v1/admin/ca` 的 `sign_public_key`，并内置到客户端）。
- `X-API-Key` 在 HTTP 下是明文的；密钥只应作为「防误用」而不当作安全边界。

---

## 8. 代码与测试索引

| 关注点 | 位置 |
|---|---|
| 产品标识 / 显示名称、平台与架构映射、挑包、检查与下载 | `core/.../update/ZooKeeperUpdateClient.java` |
| 运行形态探测、替换与重启、形态校验 | `core/.../update/SelfUpdateInstaller.java` |
| 检查时机（启动后台检查 / 设置页手动）、对话框与下载任务 | `app/.../app/update/LauncherUpdater.java`、`app/.../ui/component/UpdateDialog.java` |
| 服务地址 / 通道 / 密钥 / 开关 | `core/.../config/LauncherConfig.java` 的 `UpdateSettings`、设置页「更新」分区 |

测试（改动上述行为时至少跑这些）：

```powershell
.\gradlew.bat :core:test --tests "com.misaka.launcher.core.update.*"
```

- `ZooKeeperUpdateClientTest` 用 `com.sun.net.httpserver` 起了一个假服务端，
  覆盖检查/分块下载/校验失败/断点续传，并断言**产品标识与平台架构确实随请求发出**、
  **按运行形态挑包**、**别的平台的包不会被选中**；
- `SelfUpdateInstallerTest` 覆盖形态不符时必须拒绝替换。
