#!/bin/sh
# Misaka 启动器 - Linux / macOS 单文件自解压包
#
# 首次运行：把自带运行时解压到用户数据目录；之后直接启动（第二次起是瞬时的）。
# 之所以不用 jpackage 的 deb/dmg：那需要目标平台的构建工具链，而自解压脚本
# 在任意 POSIX 环境都能运行，且产物就是「一个文件」。
#
# 本文件是负载头，构建时会把 payload.tar.gz 原样拼接在最后一行标记之后。
# 因此第 1 行必须是 shebang，且标记行必须存在且唯一。
set -e

VERSION="@VERSION@"
APP_ID="MisakaLauncher"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
TARGET="$DATA_HOME/$APP_ID/app/$VERSION"
MARKER="$TARGET/.extracted"
LAUNCH="$TARGET/@LAUNCH_RELATIVE@"

if [ ! -f "$MARKER" ] || [ ! -x "$LAUNCH" ]; then
    echo "Misaka Launcher：首次运行，正在解压运行环境到 $TARGET"
    rm -rf "$TARGET"
    mkdir -p "$TARGET"

    # 用 grep 定位负载起始行：它比 awk 更能安全处理二进制内容
    # （grep -a 强制按文本处理，LC_ALL=C 避免多字节区域设置报错）
    START_LINE=$(LC_ALL=C grep -a -n -m1 '^__MISAKA_PAYLOAD_BELOW__$' "$0" | cut -d: -f1)
    if [ -z "$START_LINE" ]; then
        echo "错误：安装包已损坏（未找到负载标记）" >&2
        exit 1
    fi
    START_LINE=$((START_LINE + 1))

    tail -n +"$START_LINE" "$0" | tar -xz -C "$TARGET"

    # 在 Windows 上构建时归档不会保留可执行位，这里统一补齐；
    # macOS 还需要清掉隔离属性，否则会被 Gatekeeper 拦截
    chmod -R u+x "$TARGET" 2>/dev/null || true
    chmod +x "$LAUNCH" 2>/dev/null || true
    if command -v xattr >/dev/null 2>&1; then
        xattr -dr com.apple.quarantine "$TARGET" 2>/dev/null || true
    fi

    : > "$MARKER"
    echo "解压完成。"
fi

# 把自身绝对路径交给启动器：自更新要替换的就是这个脚本本身。
# $0 可能是相对路径（甚至是靠 PATH 找到的），所以先按当前目录求绝对路径再导出。
# /bin/sh 上 basename/dirname 都是内置外部命令，缺失时退化为空值 —— 启动器会在
# 拿不到该变量时退回按代码位置识别形态，不会因此启动失败。
SELF_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd) || SELF_DIR=
if [ -n "$SELF_DIR" ]; then
    MISAKA_SELF_EXE="$SELF_DIR/$(basename "$0")"
    export MISAKA_SELF_EXE
fi

exec "$LAUNCH" "$@"
exit 0
__MISAKA_PAYLOAD_BELOW__
