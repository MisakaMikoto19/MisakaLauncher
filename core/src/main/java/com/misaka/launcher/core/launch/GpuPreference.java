package com.misaka.launcher.core.launch;

import com.misaka.launcher.core.util.OsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 让游戏进程优先使用独立显卡（NVIDIA / AMD 都适用）。
 *
 * <p><b>为什么用注册表而不是 JVM 参数</b>：NVIDIA Optimus 与 AMD PowerXpress 认两种信号 ——
 * ① 可执行文件自身导出的 {@code NvOptimusEnablement} /
 * {@code AmdPowerXpressRequestHighPerformance} 符号；② Windows「图形设置」里给某个 exe
 * 指定的图形首选项。Java 进程的宿主 exe 是 {@code javaw.exe}，启动器无法往里加导出符号
 * （那需要重新链接 exe 或注入 DLL），因此只有第二条路可走 ——
 * 它恰好就是 Windows 设置中「图形设置 → 高性能」所写的位置，
 * 由系统统一分发给 NVIDIA 与 AMD 驱动，不需要区分厂商。</p>
 *
 * <p>写入位置：{@code HKCU\Software\Microsoft\DirectX\UserGpuPreferences}，
 * 值名 = 可执行文件绝对路径，值 = {@code GpuPreference=2;}（2 = 高性能，1 = 省电）。
 * 这是 Win10 1803+ 的公开机制，与用户手动在系统设置里添加完全等价。</p>
 *
 * <p>只写这一个公开位置、不碰任何驱动私有配置；写不进去（权限受限 / 精简系统）时
 * 只记录日志，绝不影响启动流程。</p>
 */
public final class GpuPreference {

    private static final Logger log = LoggerFactory.getLogger(GpuPreference.class);

    /** Windows「图形设置」保存每个应用首选项的位置。 */
    private static final String PREFERENCE_KEY =
            "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";

    /** 显示适配器类 GUID；其下每个子键（0000、0001…）描述一块显卡。 */
    private static final String DISPLAY_CLASS_KEY =
            "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}";

    /** 高性能首选值（1 = 省电）。 */
    private static final String HIGH_PERFORMANCE_VALUE = "GpuPreference=2;";

    /** 单次 reg.exe 调用的等待上限。 */
    private static final long REG_TIMEOUT_SECONDS = 10L;

    /** 本进程内已确认写好的 exe（绝对路径），避免每次启动都拉一次 reg.exe。 */
    private static final Set<String> applied = ConcurrentHashMap.newKeySet();

    /** 显示适配器名称缓存；null 表示尚未探测。 */
    private static volatile List<String> adapterNameCache;

    private GpuPreference() {
    }

    /** 本机制只在 Windows 上有效。 */
    public static boolean isSupported() {
        return OsUtils.isWindows();
    }

    /**
     * 本机是否装有独立显卡。
     *
     * <p>从显示适配器类的注册表子键读 {@code DriverDesc}：比调 WMI / PowerShell 快得多，
     * 也不受「WMIC 在新版 Windows 上已被移除」的影响。</p>
     *
     * <p>它只用于决定「值不值得费这个劲」以及给用户一个说明，不是精确分类 ——
     * AMD 的核显名字里同样带 Radeon。因此这里刻意从宽：写高性能首选项在任何机器上
     * 都是安全的，单显卡机器上 Windows 会直接忽略。</p>
     */
    public static boolean hasDiscreteGpu() {
        List<String> names = displayAdapterNames();
        if (names.isEmpty()) {
            // 探测不到就当作「可能有」：宁可多写一次注册表，也不要在有独显的机器上漏掉
            return true;
        }
        return names.stream().anyMatch(GpuPreference::looksDiscrete);
    }

    /**
     * 本机是否装有 NVIDIA 显卡。
     *
     * <p>用来做「自动」模式下的判断：NVIDIA 驱动报告的 {@code GL_MAX_TEXTURE_SIZE} 通常是
     * 32768，而 AMD / Intel 通常只有 16384 —— 图集容量差 4 倍。大型整合包在后者上会因
     * 图集装不下而崩在贴图拼接阶段，在 NVIDIA 上却能正常进。</p>
     *
     * <p>探测不到时返回 {@code false}（按「不是 N 卡」处理）：多压一次只是清晰度略有损失，
     * 漏压则是客户机直接进不去 —— 两种错的代价完全不对等。也正因为这套启发式不够可靠，
     * 贴图压缩最终由用户在设置里决定（自动 / 始终开启 / 关闭）。</p>
     */
    public static boolean hasNvidiaAdapter() {
        for (String name : displayAdapterNames()) {
            String value = name.toLowerCase(Locale.ROOT);
            if (value.contains("nvidia") || value.contains("geforce") || value.contains("quadro")
                    || value.contains("rtx") || value.contains("gtx") || value.contains("tesla")) {
                return true;
            }
        }
        return false;
    }

    /** 本机显示适配器名称（首次调用后缓存，reg 失败时为空列表）。 */
    public static List<String> displayAdapterNames() {
        List<String> cached = adapterNameCache;
        if (cached != null) {
            return cached;
        }
        List<String> names = new ArrayList<>();
        if (isSupported()) {
            String output = runReg("query", DISPLAY_CLASS_KEY, "/s", "/v", "DriverDesc");
            for (String line : output.split("\\R")) {
                String trimmed = line.trim();
                int marker = trimmed.indexOf("REG_SZ");
                if (marker < 0 || !trimmed.startsWith("DriverDesc")) {
                    continue;
                }
                String value = trimmed.substring(marker + "REG_SZ".length()).trim();
                if (!value.isEmpty()) {
                    names.add(value);
                }
            }
        }
        List<String> result = List.copyOf(names);
        adapterNameCache = result;
        return result;
    }

    /**
     * 把可执行文件登记为「高性能」图形首选项。
     *
     * @return 真表示登记后已确认生效（含「本来就已经是高性能」）
     */
    public static boolean preferHighPerformance(Path executable) {
        if (!isSupported() || executable == null) {
            return false;
        }
        String path = executable.toAbsolutePath().toString();
        if (applied.contains(path)) {
            return true;
        }
        if (isHighPerformance(path)) {
            applied.add(path);
            return true;
        }
        runReg("add", PREFERENCE_KEY, "/v", path, "/t", "REG_SZ",
                "/d", HIGH_PERFORMANCE_VALUE, "/f");
        // 只看回读结果，不解析 reg 的输出文本：中文系统的成功提示是本地化字符串，
        // 拿它判断成败会在换语言时悄悄失效
        boolean ok = isHighPerformance(path);
        if (ok) {
            applied.add(path);
            log.info("已把 {} 登记为「高性能」图形首选项", path);
        } else {
            log.warn("写入图形首选项失败（不影响启动，可手动在系统「图形设置」里指定）：{}", path);
        }
        return ok;
    }

    /** 该可执行文件当前是否已被登记为「高性能」。 */
    public static boolean isHighPerformance(Path executable) {
        if (!isSupported() || executable == null) {
            return false;
        }
        return isHighPerformance(executable.toAbsolutePath().toString());
    }

    private static boolean isHighPerformance(String path) {
        String output = runReg("query", PREFERENCE_KEY, "/v", path);
        // 输出形如：    C:\...\javaw.exe    REG_SZ    GpuPreference=2;
        return output.contains(HIGH_PERFORMANCE_VALUE);
    }

    private static boolean looksDiscrete(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("nvidia") || value.contains("geforce") || value.contains("quadro")
                || value.contains("rtx") || value.contains("gtx") || value.contains("tesla")
                || value.contains("radeon") || value.contains("intel arc");
    }

    /** 调用 reg.exe，返回合并后的输出（失败或超时返回空串）。 */
    private static String runReg(String... args) {
        List<String> command = new ArrayList<>();
        command.add("reg");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // reg 的输出很短，直接读完再等；不存在管道写满的僵局
            String output = new String(process.getInputStream().readAllBytes(),
                    Charset.defaultCharset());
            if (!process.waitFor(REG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.debug("reg {} 超时", String.join(" ", args));
                return "";
            }
            return output;
        } catch (IOException e) {
            log.debug("reg {} 执行失败：{}", String.join(" ", args), e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
