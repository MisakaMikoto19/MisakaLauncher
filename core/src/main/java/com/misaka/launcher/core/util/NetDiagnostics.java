package com.misaka.launcher.core.util;

import com.misaka.launcher.core.i18n.I18n;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import javax.net.ssl.SSLException;

/**
 * 网络故障的「翻译」与现场自检。
 *
 * <p>两件事放在一起，是因为它们服务同一个现场：用户只看到「更新失败：null」。
 * 一个负责把异常翻成结论，一个负责在失败那一刻把「解析到哪些地址、哪个地址真的通」
 * 测出来 —— 否则只能靠用户去开 PowerShell 敲命令。</p>
 *
 * <p>为什么必须翻译：{@code HttpClient} 抛出的异常<b>经常没有 message</b>。
 * 例如建连失败时它会在 {@code PlainHttpConnection.checkRetryConnect} 里对一条
 * 已经关闭的通道再连一次，于是真正的失败原因被 {@code ClosedChannelException} 顶掉，
 * 最外层只剩一个光秃秃的 {@code java.net.ConnectException} —— 直接拼
 * {@code getMessage()} 就得到「null」。</p>
 */
public final class NetDiagnostics {

    /** 顺着 cause 链最多看几层：足够穿透 HttpClient 的包装，又能防病态自引用。 */
    private static final int MAX_CAUSE_DEPTH = 8;

    private NetDiagnostics() {
    }

    /**
     * 这次失败是否属于「连接没建立起来」。
     *
     * <p>只对这种失败做重试：换一条全新连接有实际意义（见 {@code ZooKeeperUpdateClient}
     * 里的重试注释）；而请求超时、HTTP 4xx 之类的失败重试只会让用户多等一遍。</p>
     */
    public static boolean connectLevelFailure(Throwable error) {
        return findInChain(error, ConnectException.class, ClosedChannelException.class,
                NoRouteToHostException.class, SocketException.class) != null;
    }

    /**
     * 把异常翻成一句「看得懂、能动手」的话。
     *
     * <p>判定必须顺着 cause 链走：Windows 上域名解析失败经常被包成
     * {@code ConnectException(cause=UnresolvedAddressException)}，
     * 只看最外层会误报成「连接被拒绝」。</p>
     *
     * @return 永远非空，且绝不会是 {@code "null"}
     */
    public static String describe(Throwable error) {
        if (error == null) {
            return I18n.t("net.error.unknown");
        }
        String detail = firstMessage(error);
        Throwable dns = findInChain(error, UnknownHostException.class, UnresolvedAddressException.class);
        if (dns != null) {
            return I18n.t("net.error.dns", label(dns, detail));
        }
        Throwable timeout = findInChain(error, HttpTimeoutException.class, SocketTimeoutException.class);
        if (timeout != null) {
            return I18n.t("net.error.connectTimeout", label(timeout, detail));
        }
        Throwable noRoute = findInChain(error, NoRouteToHostException.class);
        if (noRoute != null) {
            return I18n.t("net.error.noRoute", label(noRoute, detail));
        }
        Throwable tls = findInChain(error, SSLException.class);
        if (tls != null) {
            return I18n.t("net.error.tls", label(tls, detail));
        }
        Throwable refused = findInChain(error, ConnectException.class, ClosedChannelException.class);
        if (refused != null) {
            return I18n.t("net.error.connectRefused", label(refused, detail));
        }
        return I18n.t("net.error.network", label(error, detail));
    }

    /**
     * 现场自检：解析主机、再逐个地址试一次 TCP 连接，返回可直接写进日志的多行文本。
     *
     * <p>只做诊断、不改任何行为：启动器的请求路径不受影响。它存在的意义是让
     * 「这台机器为什么失败」变成日志里的一句话 —— 是解析不到、还是解析到多个地址
     * 而其中某个不通（IPv6 黑洞、多 A 记录里有死节点都会长这样）。</p>
     *
     * @param url     要探测的地址（用它的主机与端口）
     * @param timeout 每个地址的 TCP 连接超时
     */
    public static String probe(String url, Duration timeout) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            return "地址无法解析为 URI：" + url;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "地址里没有主机名：" + url;
        }
        int port = uri.getPort() > 0
                ? uri.getPort()
                : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        long startedAt = System.nanoTime();
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "解析 " + host + " 失败：" + label(e, firstMessage(e))
                    + "（本机 DNS 不可用，或地址写错）";
        }
        long resolveMillis = (System.nanoTime() - startedAt) / 1_000_000;

        StringBuilder text = new StringBuilder("解析 ").append(host).append(" → ");
        for (int i = 0; i < addresses.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(addresses[i].getHostAddress());
        }
        text.append("（").append(addresses.length).append(" 个地址，").append(resolveMillis).append(" ms）");
        boolean ipv4Reachable = false;
        boolean ipv6Unreachable = false;
        for (InetAddress address : addresses) {
            boolean ipv6 = address instanceof Inet6Address;
            text.append("\n  TCP ").append(address.getHostAddress()).append(':').append(port).append(" → ");
            long began = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port),
                        (int) Math.max(200L, timeout.toMillis()));
                text.append("通（").append((System.nanoTime() - began) / 1_000_000).append(" ms）");
                ipv4Reachable |= !ipv6;
            } catch (IOException e) {
                text.append("不通（").append(e.getClass().getSimpleName()).append('，')
                        .append((System.nanoTime() - began) / 1_000_000).append(" ms）");
                ipv6Unreachable |= ipv6;
            }
        }
        if (ipv4Reachable && ipv6Unreachable) {
            // 最值得单独点出来的一种组合：Java 的 HttpClient 不做 happy eyeballs，
            // 它只会连解析结果里的第一个地址 —— 若那个是 IPv6，就会一直失败
            text.append("\n  → 仅 IPv4 可达（IPv6 不通）：启动器若仍失败，多半是它优先连了 IPv6");
        }
        return text.toString();
    }

    /** 顺着 cause 链找第一个属于给定类型的异常。 */
    @SafeVarargs
    private static Throwable findInChain(Throwable error, Class<? extends Throwable>... types) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(current)) {
                    return current;
                }
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }

    /** 链条里第一条非空 message；都没有则为空串。 */
    private static String firstMessage(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return "";
    }

    /** 「异常类名：原始 message」；没有 message 时只给类名，绝不出现 null。 */
    private static String label(Throwable error, String detail) {
        String name = error.getClass().getSimpleName();
        return detail.isBlank() ? name : name + "：" + detail;
    }
}
