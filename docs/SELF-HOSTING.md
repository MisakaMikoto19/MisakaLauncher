# 自建服务端指南

启动器有两条**互相独立**的服务端集成，可以只用一条、也可以都用：

| 链路 | 用途 | 协议 | 客户端入口 |
| --- | --- | --- | --- |
| 启动器自更新 | 检查新版本并就地替换自身 | ZooKeeper 更新协议 | 设置 → 更新 |
| 模组同步 | 启动前把服务端 `mods/` 等目录同步到本地实例 | FileSync 协议 | 设置 → 同步 |

两条都是**纯 HTTP(S) + JSON**，没有私有二进制协议，用任何语言实现都可以。下面给出每个接口的确切契约、示例 JSON、最小实现与自测命令。

---

# 一、启动器自更新

## 1.1 客户端会改的常量

服务地址在设置页可改（默认 `http://127.0.0.1:8080`），但**产品标识写死在代码里**，必须与服务端登记一致：

```java
// core/.../core/update/ZooKeeperUpdateClient.java
public static final String PRODUCT      = "MisakaLauncher"; // 产品键，区分大小写
public static final String DISPLAY_NAME = "Misaka Launcher";         // 仅用于显示
public static final String DOWNLOAD_USER = "MisakaLauncher";// 下载归属账号名
```

`PRODUCT` 改成你自己的产品键即可，服务端按它区分不同软件。

## 1.2 平台与架构取值

| 字段 | 取值 |
| --- | --- |
| `platform` | `windows` / `linux` / `darwin` / `any` |
| `arch` | `x64` / `arm64` / `any` |

客户端会上报自己的平台与架构；**服务端做一次回落（找不到精确匹配就退到 `any`），客户端拿到清单后再本地挑一次**。所以服务端返回多个文件是允许的，只要每个文件带正确的 `platform` / `arch` 元数据。

## 1.3 接口清单

### ① 健康检查

```
GET {base}/api/v1/health
```

任意 2xx 即视为可用。设置页的「测试连接」用它。

### ② 检查更新

```
POST {base}/api/v1/updates/check
Content-Type: application/json
```

请求体是**数组**（批量查询，本启动器每次只放一个元素）：

```json
[
  {
    "product": "MisakaLauncher",
    "version": "1.1.4",
    "channel": "stable",
    "platform": "windows",
    "arch": "x64"
  }
]
```

> `version` 会先做规范化（`v1.1.4` → `1.1.4`）；`channel` 可能是 `stable` / `beta` / `dev` / `alpha`，为空表示不限通道。

响应（同样是数组，顺序与请求一致）：

```json
[
  {
    "product": "MisakaLauncher",
    "has_update": true,
    "latest_version": "1.1.5",
    "channel": "stable",
    "released_at": "2026-10-20T10:00:00Z",
    "notes": "修复了 xxx",
    "notes_url": "",
    "download_url": "/api/v1/download/MisakaLauncher/1.1.5/MisakaLauncher-1.1.5-windows-portable.exe",
    "file_name": "MisakaLauncher-1.1.5-windows-portable.exe",
    "file_size": 88300000,
    "sha256": "9f2c…",
    "chunk_size": 8388608,
    "chunk_count": 11
  }
]
```

字段说明：

| 字段 | 必需 | 说明 |
| --- | --- | --- |
| `has_update` | 是 | **以服务端为准**，客户端不自行比较版本号 |
| `latest_version` | 是 | 展示用版本号 |
| `channel` / `released_at` / `notes` | 否 | 展示用 |
| `notes_url` | 否 | 给了就优先拉这个地址作为更新说明 |
| `download_url` | 有更新时必需 | 可以是绝对地址，也可以是以 `/` 开头的相对路径（客户端会按服务地址补全） |
| `file_name` / `file_size` | 否 | 缺失时从 URL 推导 |
| `sha256` | **强烈建议** | 整包校验；缺失时客户端只做长度校验 |
| `chunk_size` / `chunk_count` | 否 | 给了就走分块并行下载；不给走整包下载 |

> **分块**：只有整包 ≥ 8 MB 时客户端才会用分块（小包分块反而更慢）。给了 `chunk_count` 就必须实现下面的 `/chunks/{i}`。

### ③ 更新说明（可选）

```
GET {base}/api/v1/changelog/{product}?version={version}
```

返回任意文本/HTML 或 JSON，客户端原样展示。

### ④ 下载

```
GET {base}/api/v1/download/{product}/{version}/{file}          # 整包
GET {base}/api/v1/download/{product}/{version}/{file}/chunks   # 分块索引
GET {base}/api/v1/download/{product}/{version}/{file}/chunks/{i} # 第 i 块（0 起）
```

- 分块索引返回 JSON 数组，元素需包含每块的 `sha256`（客户端逐块校验，校验不通过就重下该块）；
- 第 `i` 块返回**二进制**，长度应等于 `chunk_size`（最后一块除外）；
- 支持 `Range` 请求可以让整包下载具备断点续传能力。

### ⑤ 请求头

客户端在**所有**更新相关请求上都会带：

```
X-ZK-User: MisakaLauncher
```

服务端可据此把下载计入该账号（未校验，仅统计）。若配置了下载口令，还会带：

```
Authorization: Basic base64(user:password)
```

若配置了 API Key 且没有口令，则带：

```
X-API-Key: zk_...
```

> 三者**只会出现一种**鉴权方式（口令 > API Key > 仅自称用户名）。服务端按需实现即可；全部不实现、返回公开内容也能用。

## 1.4 最小实现（FastAPI，可跑通）

```python
import hashlib, json
from pathlib import Path
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, JSONResponse

app = FastAPI()
BASE = "http://127.0.0.1:8080"
PRODUCT = "MisakaLauncher"
DIST = Path("./dist")          # 放要发布的文件
MANIFEST = {
    "latest_version": "1.1.5",
    "channel": "stable",
    "files": {                  # (platform, arch) -> 文件名
        ("windows", "x64"): "MisakaLauncher-1.1.5-windows-portable.exe",
        ("linux", "x64"):   "MisakaLauncher-1.1.5-linux-amd64.jar",
    },
}

@app.get("/api/v1/health")
def health():
    return {"ok": True}

@app.post("/api/v1/updates/check")
def check(items: list[dict]):
    out = []
    for item in items:
        name = MANIFEST["files"].get((item["platform"], item["arch"])) \
            or MANIFEST["files"].get((item["platform"], "any"))
        if not name:
            out.append({"product": item["product"], "has_update": False})
            continue
        path = DIST / name
        data = path.read_bytes()
        out.append({
            "product": item["product"],
            "has_update": item["version"] != MANIFEST["latest_version"],
            "latest_version": MANIFEST["latest_version"],
            "channel": MANIFEST["channel"],
            "download_url": f"/api/v1/download/{PRODUCT}/{MANIFEST['latest_version']}/{name}",
            "file_name": name,
            "file_size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        })
    return out

@app.get("/api/v1/download/{product}/{version}/{file}")
def download(product: str, version: str, file: str):
    path = DIST / file
    if not path.is_file():
        raise HTTPException(404)
    return FileResponse(path)
```

自测：

```bash
curl -s http://127.0.0.1:8080/api/v1/health
curl -s -X POST http://127.0.0.1:8080/api/v1/updates/check \
  -H "Content-Type: application/json" \
  -d '[{"product":"MisakaLauncher","version":"1.1.4","platform":"windows","arch":"x64"}]'
```

## 1.5 替换方式（客户端侧，仅供了解）

| 运行形态 | 替换动作 |
| --- | --- |
| 单文件自解压包 / 免安装目录 | 下载新包 → 校验 → 退出当前进程 → 覆盖 `app/` 与 `runtime/` → 重新拉起 |
| 安装版（jpackage） | 同上，但会走平台安装器的卸载/安装流程 |

替换前会检查「是否有正在运行的任务」，避免写到一半被打断。

---

# 二、模组同步（FileSync）

## 2.1 它做什么

把服务端指定目录（通常是 `mods/`、`config/`）的文件按 **MD5** 对齐到本地实例：

- **软同步（soft）**：只补缺失与内容变化的文件，本地多余的文件**保留**；
- **硬同步（hard）**：以服务端为准，本地多出的文件会被**删除**（先删后下）；
- **重定向（redirection）**：把服务端某目录映射到客户端另一目录（例如服务端 `mods-server/` → 客户端 `mods/`）。

客户端默认**关闭**该功能，且默认地址是 `127.0.0.1`，不会连接任何第三方主机。

## 2.2 客户端配置项

```jsonc
"sync": {
  "enabled": false,              // 总开关
  "autoSyncBeforeLaunch": true,  // 启动前自动同步（需总开关打开）
  "blockLaunchOnFailure": false, // 同步失败是否阻断启动
  "serverAddress": "127.0.0.1",
  "port": "10086"
}
```

## 2.3 接口清单（共 4 个）

### ① 健康检查

```
GET {base}/api/health
```

2xx 即视为可用；界面「测试连接」用它。

### ② 目录配置

```
GET {base}/api/config
```

```json
{
  "directories": [
    { "path": "mods", "mode": "hard" },
    { "path": "config", "mode": "soft" }
  ],
  "redirections": [
    { "from": "mods-server", "to": "mods" }
  ],
  "ignored_files": ["mods/example-client-only.jar"]
}
```

| 字段 | 说明 |
| --- | --- |
| `directories[].path` | 相对服务端工作目录的子目录 |
| `directories[].mode` | `hard` 或 `soft`（缺省按 `soft`） |
| `redirections[]` | `from` 为服务端路径前缀，`to` 为客户端路径前缀 |
| `ignored_files[]` | 不参与同步的服务端相对路径 |

> 该接口**不返回**服务端的 `port` 与全局 `mode`，客户端也不会假设它们存在。

### ③ 文件列表

```
GET {base}/api/file_list
```

```json
[
  {
    "path": "mods/sodium-0.5.11.jar",
    "real_path": "mods/sodium-0.5.11.jar",
    "size": 812345,
    "modified_time": 1760000000.0,
    "hash": "9d5c1f0a1b2c3d4e5f60718293a4b5c6",
    "mode": "hard"
  }
]
```

| 字段 | 说明 |
| --- | --- |
| `path` | 用于拼下载地址（`/api/file/{path}`） |
| `real_path` | 用于映射到客户端相对路径；与 `path` 相同是允许的 |
| `size` | 字节数 |
| `modified_time` | Unix 时间戳（秒，可为浮点） |
| `hash` | **MD5 十六进制小写**，客户端据此判断是否需要下载 |
| `mode` | 该文件所属目录的同步模式 |

### ④ 下载单个文件

```
GET {base}/api/file/{path}
```

- `path` 是相对服务端工作目录的路径，可能包含子目录与空格，需按 URL 编码处理；
- 返回**二进制原文**；
- 客户端下载后按 `hash` 校验，不匹配则判为失败（不会写入目标位置）。

## 2.4 最小实现（FastAPI）

```python
import hashlib
from pathlib import Path
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse

app = FastAPI()
ROOT = Path("./server-files").resolve()
CONFIG = {
    "directories": [{"path": "mods", "mode": "hard"}],
    "redirections": [],
    "ignored_files": [],
}

def md5(path: Path) -> str:
    h = hashlib.md5()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

@app.get("/api/health")
def health():
    return {"ok": True}

@app.get("/api/config")
def config():
    return CONFIG

@app.get("/api/file_list")
def file_list():
    items = []
    for entry in CONFIG["directories"]:
        base = (ROOT / entry["path"])
        for path in base.rglob("*"):
            if not path.is_file():
                continue
            rel = path.relative_to(ROOT).as_posix()
            if rel in CONFIG["ignored_files"]:
                continue
            stat = path.stat()
            items.append({
                "path": rel, "real_path": rel,
                "size": stat.st_size, "modified_time": stat.st_mtime,
                "hash": md5(path), "mode": entry["mode"],
            })
    return items

@app.get("/api/file/{path:path}")
def get_file(path: str):
    target = (ROOT / path).resolve()
    if not str(target).startswith(str(ROOT)) or not target.is_file():
        raise HTTPException(404)   # 防目录穿越
    return FileResponse(target)
```

自测：

```bash
curl -s http://127.0.0.1:10086/api/health
curl -s http://127.0.0.1:10086/api/config
curl -s http://127.0.0.1:10086/api/file_list | head -c 400
curl -sI "http://127.0.0.1:10086/api/file/mods/某个模组.jar"
```

## 2.5 实现要点

1. **MD5 要小写十六进制**，客户端按字符串比较；
2. `path` 里的空格与中文必须能在 `/api/file/{path}` 上原样取到（用 `{path:path}` 这类通配路由，并对 `%xx` 正确解码后做**根目录校验**）；
3. 列表里**不要**包含目录条目，只要文件；
4. 硬同步目录请谨慎：客户端会删除本地多余文件，误配会把玩家自己加的模组删掉；
5. 文件多时响应较大（上千条很正常），建议开 gzip。

---

# 三、安全建议

| 项 | 建议 |
| --- | --- |
| 传输 | 公网部署一律上 HTTPS；同步默认走 HTTP，仅适合内网 |
| 鉴权 | 更新接口可以公开；同步接口建议加内网限制或反向代理鉴权 |
| 限速 | 更新包按 IP 限速，避免被当成免费 CDN |
| 校验 | 更新包务必给 `sha256`，同步务必给正确的 `hash` |
| 目录穿越 | `/api/file/{path}` 必须做「解析后仍在根目录内」的校验 |

---

# 四、FAQ

**Q：不实现更新服务，启动器能正常用吗？**
能。设置里把服务地址清空或关掉「启动时检查更新」即可，只是不会自动升级。

**Q：同步服务能不能只用静态文件服务器？**
不能完全替代：`/api/file_list` 与 `/api/config` 需要生成 MD5 与目录配置。可以用脚本预生成 JSON 静态文件，但 `hash` 必须正确。

**Q：可以同时对接多个更新源吗？**
当前版本只支持一个服务地址。多源需要在 `ZooKeeperUpdateClient` 外层再做一层选择逻辑。

**Q：产品键能改吗？**
能，但必须与服务端登记一致，且改了之后老客户端认不出新包（更新服务的匹配是按产品键做的）。
