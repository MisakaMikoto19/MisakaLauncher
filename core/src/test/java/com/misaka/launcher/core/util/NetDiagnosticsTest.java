package com.misaka.launcher.core.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网络故障翻译与现场自检。
 *
 * <p>重点守的是那次真实现场：{@code java.net.ConnectException} 没有 message，
 * 界面于是显示「更新失败：null」。这里断言任何情况下都不会出现 {@code null} 字样，
 * 并且异常类名一定露出来（现场只有这一个线索）。</p>
 */
class NetDiagnosticsTest {

    @Test
    void connectFailureWithoutMessageShouldNotBeNull() {
        String text = NetDiagnostics.describe(new ConnectException());

        assertFalse(text.contains("null"), "提示里出现 null 等于什么都没说：" + text);
        assertTrue(text.contains("ConnectException"), "至少要露出异常类型：" + text);
    }

    @Test
    void JDK把建连失败包成ClosedChannel时也要说清楚() {
        // 现场原样复现：ConnectException → ConnectException → ClosedChannelException，全都没有 message
        ConnectException inner = new ConnectException();
        inner.initCause(new ClosedChannelException());
        ConnectException outer = new ConnectException();
        outer.initCause(inner);

        String text = NetDiagnostics.describe(outer);

        assertFalse(text.contains("null"), text);
        assertTrue(NetDiagnostics.connectLevelFailure(outer), "这类失败换一条连接重试才有意义");
    }

    @Test
    void DNS失败被包装成ConnectException时要报解析而不是被拒() {
        ConnectException wrapped = new ConnectException();
        wrapped.initCause(new UnresolvedAddressException());

        String text = NetDiagnostics.describe(wrapped);

        assertTrue(text.contains("UnresolvedAddressException"), "要说清是解析这一层的问题：" + text);
    }

    @Test
    void timeoutShouldNotBeConnectFailure() {
        // 超时重试只会让用户多等一遍，因此不能被 connectLevelFailure 放过
        assertFalse(NetDiagnostics.connectLevelFailure(
                new java.net.http.HttpTimeoutException("request timed out")));
    }

    @Test
    void selfCheckShouldDetectReachablePort() throws IOException {
        try (ServerSocket listener = new ServerSocket(0)) {
            String alive = NetDiagnostics.probe("http://127.0.0.1:" + listener.getLocalPort(),
                    Duration.ofMillis(500));
            assertTrue(alive.contains("通（"), "端口在监听就该测出通：" + alive);

            int closedPort = listener.getLocalPort();
            listener.close();
            String dead = NetDiagnostics.probe("http://127.0.0.1:" + closedPort, Duration.ofMillis(500));
            assertTrue(dead.contains("不通（"), "端口关掉后就该测出不通：" + dead);
        }
    }
}
