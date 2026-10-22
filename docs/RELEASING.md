# 发版与下载链接约定

网站的「下载」按钮是**超链接**，指向 GitHub Releases 上的文件。这些链接用的是**固定文件名**，
所以发多少版都不用改站点代码 —— 前提是每个 Release 里都上传了这几个名字的文件。

## 固定文件名（站点依赖）

| 平台 | 固定名 | 来源（构建自动产出） |
| --- | --- | --- |
| Windows 10/11 x64 | `MisakaLauncher-windows-x64.exe` | 单文件自解压包 |
| Linux x64 | `MisakaLauncher-linux-x64.jar` | 交叉编译的可执行 jar |
| macOS（Apple 芯片） | `MisakaLauncher-macos-arm64.jar` | 交叉编译的可执行 jar |
| macOS（Intel） | `MisakaLauncher-macos-x64.jar` | 交叉编译的可执行 jar |

**这些文件由构建自动生成，不需要手工复制改名**（`releaseDist` 任务会把固定名副本连同带版本号的原件
一起写进 `dist/`）。

对应链接：

```
https://github.com/MisakaMikoto19/MisakaLauncher/releases/latest/download/MisakaLauncher-windows-x64.exe
https://github.com/MisakaMikoto19/MisakaLauncher/releases/latest/download/MisakaLauncher-linux-x64.jar
https://github.com/MisakaMikoto19/MisakaLauncher/releases/latest/download/MisakaLauncher-macos-arm64.jar
https://github.com/MisakaMikoto19/MisakaLauncher/releases/latest/download/MisakaLauncher-macos-x64.jar
「所有版本」→ https://github.com/MisakaMikoto19/MisakaLauncher/releases
```

> 为什么不能直接链带版本号的文件：`/releases/latest/download/<文件名>` 是按**资产名**解析的，
> 下一个版本号变了，旧名字就 404。

## 发版流程

1. 改 `gradle.properties` 的 `misaka.version`，跑 `build_all.ps1`（或 `build_all.sh`）；
2. 确认 `dist/` 里同时有固定名与带版本号的两套文件（构建日志会打印「站点固定名资产：…」）；
3. GitHub → Releases → **Draft a new release**
   - Tag：`v<版本>`（新建）
   - Title：`Misaka Launcher <版本>`
   - 附件：见下表
   - Publish release

| 上传哪些 | 是否必需 | 用途 |
| --- | --- | --- |
| 4 个固定名文件 | **必需** | 站点四个下载按钮 |
| `MisakaLauncher-<版本>-*.jar` + `.sha256` | 可选 | 自更新服务按版本登记与校验 |
| `release.json` / `SHA256SUMS.txt` | 可选 | 校验与清单 |

## GitHub Actions 的自动化

`.github/workflows/release.yml` 在推送 `v*` 标签时会：

1. 三平台各建一次免安装版（目录形态，打成 zip）；
2. Windows job 额外跑 `:app:releaseDist`，产出 `MisakaLauncher-windows-x64.exe`；
3. 单独的 `site-assets` job 用 `:app:distJarAll` 交叉编译三个 jar 并改成固定名；
4. `release` job 把目录 zip 与 4 个固定名文件一起挂到 Release 上。

所以打标签发版时，这 4 个名字会**自动出现**，不需要手工处理。

## 发版后自检

```powershell
# 应返回 302，Location 指向实际资产地址
curl.exe -sI "https://github.com/MisakaMikoto19/MisakaLauncher/releases/latest/download/MisakaLauncher-windows-x64.exe" |
  Select-String -Pattern "HTTP/|location:"
```

再确认三件事：

- Release 页面能看到 `v<版本>` 标签，且四个固定名都在附件里；
- 网站首页的下载按钮点下去能真的开始下载（不是跳到 404 页面）；
- 文件大小与 `dist/` 里的一致（避免传了空文件或未完成的包）。

## 与自更新服务的关系

`docs/SELF-HOSTING.md` 里的更新服务按**产品键 + 版本号**返回下载地址，与这里的固定名无关 ——
两套发布并行：GitHub Releases 面向人类下载，ZooKeeper 服务面向启动器自更新。
因为 `dist/` 里两套文件并存，两边都不缺料。
