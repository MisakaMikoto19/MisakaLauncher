#!/usr/bin/env bash
#
# 打包发布（Linux / macOS）：构建并把发布产物收集到项目根目录 dist/。
#
# 产物全部落在 dist/：
#     MisakaLauncher-<版本>-<平台>-portable.sh          单文件发布包（推荐分发）
#     MisakaLauncher-<版本>-<平台>-portable.tar.gz      免安装版归档（带顶层目录）
#     <可选> MisakaLauncher-<版本>.deb / .dmg           平台安装包（需 --installer）
#     SHA256SUMS.txt / release.json / README.txt                校验、清单与使用说明
#
# 重复执行不会重复编译：Gradle 会自动跳过未变化的步骤。
#
# 用法:
#     ./release.sh                 构建 + 测试 + 收集到 dist/
#     ./release.sh --skip-tests    跳过测试（发布前不建议）
#     ./release.sh --installer     先生成平台安装包并一并收入 dist/
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
            sed -n '2,16p' "$0"
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
    echo "发布完成但未找到 dist 目录: $DIST_DIR" >&2
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
