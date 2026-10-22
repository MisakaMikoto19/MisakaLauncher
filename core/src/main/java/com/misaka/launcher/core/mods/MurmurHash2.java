package com.misaka.launcher.core.mods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MurmurHash2（32 位）实现，用于计算 CurseForge 官方文件指纹。
 * <p>CurseForge 的指纹算法为：先移除文件内容中的所有空白类字节
 * （{@code 0x09} 制表符、{@code 0x0A} 换行、{@code 0x0D} 回车、{@code 0x20} 空格），
 * 再以种子 1 计算 MurmurHash2，最后取低 32 位作为无符号数。</p>
 * <p>该算法仅用于「由本地文件反查 CurseForge 版本」这一可选能力；
 * 已通过启动器安装的模组会记录 projectId 与 fileId，因此即便指纹无法匹配也不会影响更新检查。</p>
 */
public final class MurmurHash2 {

    private static final int M = 0x5bd1e995;
    private static final int R = 24;

    /** CurseForge 使用的种子。 */
    public static final int CURSEFORGE_SEED = 1;

    private MurmurHash2() {
    }

    /** 计算 32 位 MurmurHash2。 */
    public static int hash32(byte[] data, int seed) {
        int length = data.length;
        int h = seed ^ length;
        int index = 0;
        int remaining = length;

        while (remaining >= 4) {
            int k = (data[index] & 0xff)
                    | ((data[index + 1] & 0xff) << 8)
                    | ((data[index + 2] & 0xff) << 16)
                    | ((data[index + 3] & 0xff) << 24);
            k *= M;
            k ^= k >>> R;
            k *= M;

            h *= M;
            h ^= k;

            index += 4;
            remaining -= 4;
        }

        switch (remaining) {
            case 3 -> {
                h ^= (data[index + 2] & 0xff) << 16;
                h ^= (data[index + 1] & 0xff) << 8;
                h ^= data[index] & 0xff;
                h *= M;
            }
            case 2 -> {
                h ^= (data[index + 1] & 0xff) << 8;
                h ^= data[index] & 0xff;
                h *= M;
            }
            case 1 -> {
                h ^= data[index] & 0xff;
                h *= M;
            }
            default -> {
                // 无剩余字节
            }
        }

        h ^= h >>> 13;
        h *= M;
        h ^= h >>> 15;
        return h;
    }

    /** 计算 CurseForge 指纹（截断空白字节 + 种子 1 + 无符号化）。 */
    public static long fingerprint(byte[] data) {
        return hash32(stripWhitespace(data), CURSEFORGE_SEED) & 0xFFFFFFFFL;
    }

    /** 计算本地文件的 CurseForge 指纹。 */
    public static long fingerprint(Path file) throws IOException {
        return fingerprint(Files.readAllBytes(file));
    }

    /** 移除 CurseForge 指纹算法要求忽略的空白字节。 */
    public static byte[] stripWhitespace(byte[] data) {
        int count = 0;
        for (byte b : data) {
            if (!isIgnored(b)) {
                count++;
            }
        }
        if (count == data.length) {
            return data;
        }
        byte[] result = new byte[count];
        int index = 0;
        for (byte b : data) {
            if (!isIgnored(b)) {
                result[index++] = b;
            }
        }
        return result;
    }

    private static boolean isIgnored(byte value) {
        return value == 9 || value == 10 || value == 13 || value == 32;
    }
}
