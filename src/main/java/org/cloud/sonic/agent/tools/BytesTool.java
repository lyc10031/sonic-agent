/*
 *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
 *   Copyright (C) 2022 SonicCloudOrg
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published
 *   by the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.cloud.sonic.agent.tools;

import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author ZhouYiXun
 * @des
 * @date 2021/8/26 22:23
 */
@Slf4j
public class BytesTool {

    public static int agentId = 0;
    public static String agentHost = "";
    public static int highTemp = 0;
    public static int highTempTime = 0;

    public static int remoteTimeout = 480;

    // 预编译正则表达式（性能提升关键点）
    private static final Pattern INT_PATTERN = Pattern.compile("[0-9]+");
    private static final Pattern NON_DIGIT_PATTERN = Pattern.compile("[^0-9]");


    // 优化toInt方法（减少循环次数）
    public static int toInt(byte[] b) {
        int res = 0;
        for (int i = 0; i < b.length; i++) {
            res |= (b[i] & 0xFF) << (i * 8);
        }
        return res;
    }

    // 优化intToByteArray（使用预计算位移）
    public static byte[] intToByteArray(int i) {
        return new byte[] {
                (byte) i,
                (byte) (i >> 8),
                (byte) (i >> 16),
                (byte) (i >> 24)
        };
    }

    // 优化subByteArray（避免重复计算长度）
    public static byte[] subByteArray(byte[] byte1, int start, int end) {
        int length = end - start;
        byte[] byte2 = new byte[length];
        System.arraycopy(byte1, start, byte2, 0, length);
        return byte2;
    }

    public static long bytesToLong(byte[] src, int offset) {
        long value;
        value = ((src[offset] & 0xFF) | ((src[offset + 1] & 0xFF) << 8) | ((src[offset + 2] & 0xFF) << 16)
                | ((src[offset + 3] & 0xFF) << 24));
        return value;
    }

    // java合并两个byte数组
    public static byte[] addBytes(byte[] data1, byte[] data2) {
        byte[] data3 = new byte[data1.length + data2.length];
        System.arraycopy(data1, 0, data3, 0, data1.length);
        System.arraycopy(data2, 0, data3, data1.length, data2.length);
        return data3;
    }

    public static void sendByte(Session session, byte[] message) {
        sendInternal(session, () -> session.getBasicRemote().sendBinary(ByteBuffer.wrap(message)), "二进制流");
    }


    public static void sendByte(Session session, ByteBuffer message) {
        sendInternal(session, () -> session.getBasicRemote().sendBinary(message), "二进制流");
    }

    public static void sendText(Session session, String message) {
        sendInternal(session, () -> session.getBasicRemote().sendText(message), "文本");
    }

    // 公共发送逻辑封装
    private static void sendInternal(Session session, CheckedSender sender, String type) {
        if (session == null || !session.isOpen()) return;

        synchronized (session) {
            try {
                // 双重检查确保连接状态
                if (!session.isOpen()) {
                    log.debug("连接已提前关闭，放弃发送");
                    return;
                }
                sender.send();
            } catch (IllegalStateException e) {
                log.debug("WebSocket {}发送失败：连接已关闭", type);
                closeSession(session); // 增加关闭操作
            } catch (IOException e) {
                if (e.getMessage().contains("Broken pipe")) {
                    log.warn("检测到连接中断，终止发送");
                    closeSession(session); // 立即关闭连接
                } else {
                    log.error("WebSocket {}发送IO异常", type, e);
                }
            }
        }
    }
    // 新增同步关闭方法
    private static synchronized void closeSession(Session session) {
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException ex) {
                log.trace("连接已自然关闭");
            }
        }
    }
    @FunctionalInterface
    private interface CheckedSender {
        void send() throws IOException;
    }

    // 优化正则匹配方法（使用预编译Pattern）
    public static boolean isInt(String s) {
        return INT_PATTERN.matcher(s).matches();
    }

    public static int getInt(String a) {
        Matcher m = NON_DIGIT_PATTERN.matcher(a);
        return Integer.parseInt(m.replaceAll("").trim());
    }

    public static boolean versionCheck(String target, String local) {
        int[] targetParse = parseVersion(target);
        int[] localParse = parseVersion(local);
        if (targetParse[0] < localParse[0]) {
            return true;
        }
        if (targetParse[0] == localParse[0]) {
            if (targetParse[1] < localParse[1]) {
                return true;
            }
            if (targetParse[1] == localParse[1]) {
                if (targetParse[2] <= localParse[2]) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int[] parseVersion(String s) {
        String[] parts = s.split("\\.");
        int[] ver = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            ver[i] = Integer.parseInt(parts[i]);
        }
        return ver;
    }
}
