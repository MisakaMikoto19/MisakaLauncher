#!/usr/bin/env bash
#
# 一键构建 Misaka Launcher（Linux / macOS）。
#
# 构建并把发布产物收集到项目根目录 dist/：
#     dist/MisakaLauncher-<版本>-<平台>-portable.sh      —— 单文件发布包（推荐分发）
#     dist/MisakaLauncher-<版本>-<平台>-portable.tar.gz  —— 免安装版归档（带顶层目录）
#     dist/MisakaLauncher-<版本>-windows-amd64.jar       —— 可执行 jar（交叉编译，目标机需自备 Java 17）
#     dist/MisakaLauncher-<版本>-linux-amd64.jar         —— 同上，Linux 版；两个平台一次构建一起产出
#     dist/SHA256SUMS.txt / release.json / README.txt             —— 校验、清单与使用说明
#
# jar 与单文件包是两种形态：jar 不带 JVM（因此能在任意平台交叉构建），
# 单文件包自带运行时（目标机免装 Java，但只能在本平台构建）。
#
# 中间产物（免安装版目录、自带 Java 运行时）仍保留在 build_linux|macos/，便于本地调试。
#
# 单文件发布包 = POSIX 自解压脚本 + 尾部拼接的 payload.tar.gz：
# 首次运行解压到 $XDG_DATA_HOME（macOS 为 ~/.local/share），之后启动是瞬时的。
#
# 构建依赖全部走国内镜像：
#     - Gradle 分发包  : 腾讯云镜像（gradle/wrapper/gradle-wrapper.properties）
#     - Maven 依赖仓库 : 阿里云镜像（settings.gradle.kts）
#
# 用法:
#     ./build_all.sh              编译 + 测试 + 单文件 → dist/
#     ./build_all.sh --skip-tests 跳过测试
#     ./build_all.sh --installer  额外生成安装包（Linux 需 dpkg/rpm，macOS 原生支持）
#
set -euo pipefail

cd "$(dirname "$0")"

SKIP_TESTS=0
INSTALLER=0
for arg in "$@"; do
    case "$arg" in
        --skip-tests) SKIP_TESTS=1 ;;
        --installer)  INSTALLER=1 ;;
        -h|--help)
            sed -n '2,20p' "$0"
            exit 0
            ;;
        *)
            echo "未知参数: $arg" >&2
            exit 2
            ;;
    esac
done

if [ ! -x "./gradlew" ]; then
    chmod +x ./gradlew 2>/dev/null || true
fi

export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"

# 安装包必须先单独跑完：releaseDist 是在 doLast 里从 build_<os>/ 收集产物的，
# 与 packageInstaller 放进同一次调用无法保证先后顺序
if [ "$INSTALLER" -eq 1 ]; then
    echo "==> gradlew :app:packageInstaller"
    ./gradlew :app:packageInstaller --console=plain
fi

TASKS=()
if [ "$SKIP_TESTS" -eq 0 ]; then
    TASKS+=(":core:test")
fi
TASKS+=(":app:releaseDist")

echo "==> gradlew ${TASKS[*]}"
./gradlew "${TASKS[@]}" --console=plain

DIST_DIR="dist"
if [ ! -d "$DIST_DIR" ]; then
    echo "构建完成但未找到 dist 目录: $DIST_DIR" >&2
    exit 1
fi

echo
echo "==> 发布产物（dist/）"
ls -lh "$DIST_DIR" | awk 'NR > 1 { printf "    %-56s %s\n", $9, $5 }'

PORTABLE=$(find "$DIST_DIR" -maxdepth 1 -name '*-portable.sh' | head -n 1)
if [ -n "$PORTABLE" ]; then
    echo
    echo "对外分发只需这一个文件: $PORTABLE"
fi
