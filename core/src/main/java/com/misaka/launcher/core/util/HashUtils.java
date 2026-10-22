package com.misaka.launcher.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 哈希工具。
 * <p>分块大小与 FileSync 客户端 / 服务端保持一致的 4096 字节，用于同步协议比对；
 * 其余场景同样复用该分块大小以保证行为统一。</p>
 */
public final class HashUtils {

    /** 与 FileSyncApp 的 Python 实现保持一致的读取分块大小。 */
    public static final int CHUNK_SIZE = 4096;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HashUtils() {
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持算法: " + algorithm, e);
        }
    }

    public static String sha1(Path file) throws IOException {
        return hash(file, "SHA-1");
    }

    public static String md5(Path file) throws IOException {
        return hash(file, "MD5");
    }

    public static String sha1(byte[] data) {
        return toHex(digest("SHA-1").digest(data));
    }

    public static String sha256(Path file) throws IOException {
        return hash(file, "SHA-256");
    }

    /** SHA-256 十六进制串（Java 发行版元数据使用该算法）。 */
    public static String sha256Hex(byte[] data) {
        return toHex(digest("SHA-256").digest(data));
    }

    public static String md5(byte[] data) {
        return toHex(digest("MD5").digest(data));
    }

    public static String hash(Path file, String algorithm) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("文件不存在或不是普通文件: " + file);
        }
        MessageDigest md = digest(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                md.update(buffer, 0, read);
            }
        }
        return toHex(md.digest());
    }

    /**
     * 校验文件哈希；当 {@code expected} 为空时视为通过（用于无哈希信息的自定义源）。
     *
     * @param algorithm 例如 SHA-1 / MD5，大小写不敏感
     */
    public static boolean verify(Path file, String expected, String algorithm) throws IOException {
        if (expected == null || expected.isBlank()) {
            return Files.isRegularFile(file);
        }
        String actual = hash(file, algorithm);
        return actual.equalsIgnoreCase(expected.trim());
    }

    /**
     * 由期望值的十六进制长度推断算法。
     * <p>元数据来源不同，提供的校验算法也不同：Mojang / BMCLAPI 用 SHA-1(40)，
     * Adoptium 只提供 SHA-256(64)，老旧资源偶见 MD5(32)。
     * 统一按长度自动识别，可以让下载链路用同一套代码支持全部来源。</p>
     *
     * @return 算法名；长度无法识别时返回 null
     */
    public static String algorithmFor(String expected) {
        if (expected == null) {
            return null;
        }
        int length = expected.trim().length();
        return switch (length) {
            case 64 -> "SHA-256";
            case 40 -> "SHA-1";
            case 32 -> "MD5";
            default -> null;
        };
    }

    /**
     * 按期望值长度自动选择算法进行校验。
     *
     * @param expected 期望的十六进制校验值；为空视为只判断文件存在
     * @throws IOException 长度无法识别（避免「静默跳过校验」这种最危险的行为）
     */
    public static boolean verifyAuto(Path file, String expected) throws IOException {
        if (expected == null || expected.isBlank()) {
            return Files.isRegularFile(file);
        }
        String algorithm = algorithmFor(expected);
        if (algorithm == null) {
            throw new IOException("无法识别的校验值长度（" + expected.trim().length() + "）: " + expected);
        }
        return verify(file, expected, algorithm);
    }

    /** 按期望值长度自动选择算法计算哈希。 */
    public static String hashAuto(Path file, String expected) throws IOException {
        String algorithm = algorithmFor(expected);
        if (algorithm == null) {
            throw new IOException("无法识别的校验值长度（"
                    + (expected == null ? 0 : expected.trim().length()) + "）");
        }
        return hash(file, algorithm);
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
