# Misaka Launcher

[简体中文](README.md) | **English**

A cross-platform (Windows 10/11, Linux, macOS) Minecraft launcher. It brings its own Java runtime management: for each game version it picks the right major version, downloads it and installs it next to the launcher, so the target machine needs no preinstalled environment.

> **This is an unofficial third-party launcher.** It is not affiliated with, endorsed by, or sponsored by Mojang Studios or Microsoft.
> "Minecraft" is a trademark of Mojang Studios; it is used here only to describe compatibility.

---

## Features

| Area | Capability |
| --- | --- |
| Versions & instances | Isolated instances; each one has its own version, mods, resource packs, saves and launch arguments |
| Version install | Vanilla install; the **official CDN is used by default**, falling back to a mirror only when it is unreachable; BMCLAPI or a self-hosted mirror can be selected in settings |
| Mod loaders | Forge, NeoForge, Fabric, Quilt, OptiFine |
| Java runtime | Detects the required major version per Minecraft version, then downloads and installs it next to the launcher |
| Accounts | **Microsoft sign-in only** (browser loopback callback + device code fallback); no offline accounts and no third-party auth servers |
| Download center | Search and install from Modrinth / CurseForge, with popularity ranking, categories, pagination and thumbnails |
| Mods & resource packs | Update checks from CurseForge / Modrinth / a direct URL; enable, disable and drag-to-reorder resource packs |
| Modpacks | Import local `.zip` / `.mrpack`; install straight from search results |
| Pre-launch sync | Reimplementation of the FileSync protocol (`/api/file_list`, `/api/config`, `/api/file/<path>`) with soft/hard sync, redirections and MD5 verification |
| Texture compatibility | On AMD / Intel GPUs the texture atlas limit is lower; a generated override pack prevents atlas-stitching crashes |
| Self-update | Works with a self-hosted ZooKeeper update service: per-platform picking, chunked verification, in-place replacement |
| UI | Grouped sidebar navigation plus a persistent launch card; glassmorphism styling and animated backgrounds |

---

## Two values you must fill in (important)

This repository ships **no credentials**. Both values are empty in the source and are injected at **build time** — no source edit needed after forking:

| Credential | Injection name | If not injected |
| --- | --- | --- |
| Microsoft OAuth app (public client) | `MISAKA_MS_CLIENT_ID` | Microsoft sign-in is unavailable; the account page says the build has no application ID |
| CurseForge catalog API key | `MISAKA_CURSEFORGE_API_KEY` | That source shows as "not configured" and the download center falls back to Modrinth |

Where the values come from (highest priority first):

```powershell
# 1) Environment variables (CI injects them from GitHub Secrets)
$env:MISAKA_MS_CLIENT_ID = '...'
$env:MISAKA_CURSEFORGE_API_KEY = '...'

# 2) Gradle properties in ~/.gradle/gradle.properties (never the one in the repo)
#    misaka.microsoftClientId=...
#    misaka.curseforgeApiKey=...
```

`core:generateBuildSecrets` turns them into compile-time constants of `BuildSecrets`
(generated under `core/build/generated/`, never committed). CI reads the same-named
environment variables from GitHub Secrets; locally use the variables above or your
user-level `gradle.properties`.

Neither value can be **overridden at runtime** — by design. They identify *who* is calling the API, so allowing runtime overrides would let anyone impersonate someone else's app registration.

### Getting and registering a Microsoft Client ID

1. Register an application in the Azure portal with account type "Personal Microsoft accounts only";
2. Choose the "Public client / Mobile and desktop" platform and set the redirect URI to `http://localhost`;
3. Set "Authentication → Allow public client flows" to Yes;
4. You **must** submit it to Mojang for review at <https://aka.ms/AppRegInfo>. Only approved apps may exchange Minecraft tokens; otherwise you get `Invalid app registration`.

What the application should contain: a reachable website, a privacy policy, a terms page, a clear description of what the app does, a downloadable release, and the open-source repository URL. Rejections have no appeal channel — improve the material and apply again.

### CurseForge key

Request one at <https://console.curseforge.com> (keys look like `$2a$10$…`). It is only used to read public mod metadata; do not use it for write operations.

---

## Requirements

- **Running**: Windows 10/11 x64, mainstream Linux x64, macOS 11+ (Intel / Apple Silicon); no Java needed on the target machine
- **Building**: JDK 17 (must provide both `javac` and `jmods`, used by jlink/jpackage)

## Building

```powershell
# Windows
.\build_all.ps1                 # compile + test + package
.\build_all.ps1 -SkipTests      # skip tests
.\build_all.ps1 -Installer      # also build installers (requires WiX Toolset)
```

```bash
# Linux / macOS
./build_all.sh
```

Artifacts are written to `dist/`:

| File | Notes |
| --- | --- |
| `MisakaLauncher-windows-x64.exe` | **Stable name used by the website's download buttons** (single self-extracting executable) |
| `MisakaLauncher-linux-x64.jar` / `-macos-arm64.jar` / `-macos-x64.jar` | Same, as cross-built jars |
| `MisakaLauncher-<version>-windows-portable.exe` | Versioned original (archive / self-update service) |
| `MisakaLauncher-<version>-<platform>-<arch>.jar` + `.sha256` | Cross-built executable jar; the target machine needs Java 17 |
| `MisakaLauncher-<version>-windows-portable.zip` | Portable directory archive |

> The stable names and the versioned files are byte-identical and produced by the same build; the stable names keep the website's links unchanged across releases. See `docs/RELEASING.md`.

> Both the artifact names and the update service product key derive from `appId` (`MisakaLauncher`) in `app/build.gradle.kts`, so renaming is a single edit.
> The historical identifier `MisakaMinecraftLauncher` is retired (the product name no longer contains the Minecraft trademark). Because the product key changed, older clients only know the old key; the migration order is documented in `docs/ZooKeeper接入说明.md`.

---

## Documentation

| Document | Content |
| --- | --- |
| [`docs/SELF-HOSTING.md`](docs/SELF-HOSTING.md) | **Self-hosting guide**: the 3 endpoints an update service must implement and the 4 endpoints mod sync needs, with request/response examples and a minimal implementation |
| [`docs/ZooKeeper接入说明.md`](docs/ZooKeeper接入说明.md) | Full details for integrating with a self-hosted ZooKeeper update service (platform mapping, release process, troubleshooting) |
| [`使用说明.md`](使用说明.md) | End-user guide |

> The documents above are currently written in Chinese.

---

## Project layout

```
app/        JavaFX UI, launch pipeline, task center, page wiring
core/       UI-independent core logic: install, download, auth, mods, sync, update, config, i18n
packaging/  jpackage / self-extracting packaging resources
docs/       Public documentation
```

- The UI layer depends only on the `AppShell` interface; the core layer references no JavaFX types.
- Long-running work goes through `TaskCenter` (cancellable, concurrent, aggregates progress).

## Contributing

1. Fork the repository and create a branch;
2. `./gradlew :core:test` must be green (core logic has fairly complete unit tests);
3. Explain the motivation and the impact in your PR, and attach screenshots for UI changes.

**Never commit real API keys, client IDs, account tokens or personal paths in a PR.**

## License

[MIT](LICENSE)

Third-party components shipped with the build — including the bundled CJK font,
which is **SIL OFL 1.1** — are listed in [THIRD-PARTY.md](THIRD-PARTY.md).
