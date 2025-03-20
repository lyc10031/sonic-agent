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
package org.cloud.sonic.agent.tests.android.minicap;

import com.alibaba.fastjson.JSONObject;
import jakarta.websocket.Session;
import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
import org.cloud.sonic.agent.tools.BytesTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 视频流输出线程
 *
 * @author Eason(main) JayWenStar(until e1a877b7)
 * @date 2021/12/2 12:12 上午
 */
public class MiniCapOutputSocketThread extends Thread {

    // 最大图片列表大小
    private static final int MAX_IMG_LIST_SIZE = 100;
    private final Logger log = LoggerFactory.getLogger(MiniCapOutputSocketThread.class);

    /**
     * 占用符逻辑参考：{@link AndroidTestTaskBootThread#ANDROID_TEST_TASK_BOOT_PRE}
     */
    public final static String ANDROID_OUTPUT_SOCKET_PRE = "android-minicap-output-socket-task-%s-%s-%s";

    private MiniCapInputSocketThread sendImg;

    private AtomicReference<String[]> banner;

    private AtomicReference<List<byte[]>> imgList;

    private Session session;

    private String pic;

    private String udId;

    private AndroidTestTaskBootThread androidTestTaskBootThread;

    public MiniCapOutputSocketThread(
            MiniCapInputSocketThread sendImg,
            AtomicReference<String[]> banner,
            AtomicReference<List<byte[]>> imgList,
            Session session,
            String pic
    ) {
        this.sendImg = sendImg;
        this.banner = banner;
        this.imgList = imgList;
        this.session = session;
        this.pic = pic;
        this.androidTestTaskBootThread = sendImg.getAndroidTestTaskBootThread();

        this.setDaemon(true);
        this.setName(androidTestTaskBootThread.formatThreadName(ANDROID_OUTPUT_SOCKET_PRE));
    }

    public boolean sessionOpen() {
        return session != null && session.isOpen();
    }

    @Override
    public void run() {

        int readBannerBytes = 0;
        int bannerLength = 2;
        int readFrameBytes = 0;
        int frameBodyLength = 0;
        byte[] frameBody = new byte[0];
        byte[] oldBytes = new byte[0];
        int count = 0;
        // 新增缓冲区管理变量
        byte[] frameBuffer = new byte[1024 * 1024]; // 预分配1MB缓冲区
        int bufferWritePos = 0;

        BlockingQueue<byte[]> dataQueue = sendImg.getDataQueue();
        while (sendImg.isAlive()) {
            byte[] buffer = new byte[0];
            try {
                buffer = dataQueue.take();
            } catch (InterruptedException e) {
                log.debug("获取数据流中断：", e);
                return;
            }
            int len = buffer.length;
            for (int cursor = 0; cursor < len; ) {
                int byte10 = buffer[cursor] & 0xff;
                if (readBannerBytes < bannerLength) {//第一次进来读取头部信息
                    switch (readBannerBytes) {
                        case 0:
                            // version
                            banner.get()[0] = buffer[cursor] + "";
                            break;
                        case 1:
                            // length
                            bannerLength = buffer[cursor];
                            banner.get()[1] = String.valueOf(bannerLength);
                            break;
                        case 2:
                        case 3:
                        case 4:
                        case 5:
                            banner.get()[5] = BytesTool.bytesToLong(buffer, 2) + "";
                            break;
                        case 6:
                        case 7:
                        case 8:
                        case 9:
                            banner.get()[9] = BytesTool.bytesToLong(buffer, 6) + "";
                            break;
                        case 10:
                        case 11:
                        case 12:
                        case 13:
                            banner.get()[13] = BytesTool.bytesToLong(buffer, 10) + "";
                            break;
                        case 14:
                        case 15:
                        case 16:
                        case 17:
                            banner.get()[17] = BytesTool.bytesToLong(buffer, 14) + "";
                            break;
                        case 18:
                        case 19:
                        case 20:
                        case 21:
                            banner.get()[21] = BytesTool.bytesToLong(buffer, 18) + "";
                            break;
                        case 22:
                            banner.get()[22] += buffer[cursor] * 90;
                            break;
                        case 23:
                            // quirks
                            banner.get()[23] = buffer[cursor] + "";
                            break;
                    }
                    cursor += 1;
                    readBannerBytes += 1;
                    if (readBannerBytes == bannerLength) {
                        log.info("banner读取已就绪");
                        if (sessionOpen()) {
                            JSONObject size = new JSONObject();
                            size.put("msg", "size");
                            size.put("width", banner.get()[9]);
                            size.put("height", banner.get()[13]);
                            BytesTool.sendText(session, size.toJSONString());
                        }
                    }
                } else if (readFrameBytes < 4) {//读取并设置图片的大小
                    frameBodyLength += (byte10 << (readFrameBytes * 8));
                    cursor += 1;
                    readFrameBytes += 1;
                } else {
                    if (len - cursor >= frameBodyLength) {
//                        byte[] subByte = BytesTool.subByteArray(buffer, cursor,
//                                cursor + frameBodyLength);
//                        frameBody = BytesTool.addBytes(frameBody, subByte);
//                        if ((frameBody[0] != -1) || frameBody[1] != -40) {
//                            return;
//                        }
//                        final byte[] finalBytes = BytesTool.subByteArray(frameBody,
//                                0, frameBody.length);
//
                        // 检查缓冲区容量并按需扩容
                        if (bufferWritePos + frameBodyLength > frameBuffer.length) {
                            int newSize = Math.max(bufferWritePos + frameBodyLength, frameBuffer.length * 2);
                            frameBuffer = Arrays.copyOf(frameBuffer, newSize);
                        }

                        // 直接写入缓冲区
                        System.arraycopy(buffer, cursor, frameBuffer, bufferWritePos, frameBodyLength);
                        bufferWritePos += frameBodyLength;

                        // JPEG头校验（0xFFD8）
                        if (bufferWritePos < 2 || (frameBuffer[0] != (byte)0xFF) || (frameBuffer[1] != (byte)0xD8)) {
                            log.warn("Invalid JPEG header detected, reset buffer");
                            bufferWritePos = 0;
                            continue;
                        }

                        // 处理完整帧数据
                        final byte[] finalBytes = Arrays.copyOfRange(frameBuffer, 0, bufferWritePos);
                        bufferWritePos = 0; // 重置写指针
                        if (sessionOpen()) {
                            if (!Arrays.equals(oldBytes, finalBytes)) {
                                switch (pic) {
                                    case "low":
                                        count++;
                                        break;
                                    case "middle":
                                    case "fixed":
                                        count += 2;
                                        break;
                                    case "high":
                                        break;
                                }
                                if (count % 4 == 0) {
                                    count = 0;
                                    oldBytes = finalBytes;
                                    BytesTool.sendByte(session, finalBytes);
                                }
                            }
                        }
                        if (imgList != null) {
                            // 防止内存溢出
                            List<byte[]> list = imgList.get();
                            if (list.size() >= MAX_IMG_LIST_SIZE) {
                                // 如果列表达到最大容量，移除最早添加的元素
                                list.remove(0);
                            }
                            imgList.get().add(finalBytes);
                        }
                        cursor += frameBodyLength;
                        frameBodyLength = 0;
                        readFrameBytes = 0;
                        frameBody = new byte[0];
                    } else {
//                        byte[] subByte = BytesTool.subByteArray(buffer, cursor, len);
//                        frameBody = BytesTool.addBytes(frameBody, subByte);
//                        frameBodyLength -= (len - cursor);
//                        readFrameBytes += (len - cursor);
//                        cursor = len;
                        // 部分数据写入缓冲区
                        int remaining = len - cursor;
                        if (bufferWritePos + remaining > frameBuffer.length) {
                            int newSize = Math.max(bufferWritePos + remaining, frameBuffer.length * 2);
                            frameBuffer = Arrays.copyOf(frameBuffer, newSize);
                        }
                        System.arraycopy(buffer, cursor, frameBuffer, bufferWritePos, remaining);
                        bufferWritePos += remaining;
                        frameBodyLength -= remaining;
                        cursor = len;
                    }
                }
            }
        }
    }
}

//
///*
// *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
// *   Copyright (C) 2022 SonicCloudOrg
// *
// *   This program is free software: you can redistribute it and/or modify
// *   it under the terms of the GNU Affero General Public License as published
// *   by the Free Software Foundation, either version 3 of the License, or
// *   (at your option) any later version.
// *
// *   This program is distributed in the hope that it will be useful,
// *   but WITHOUT ANY WARRANTY; without even the implied warranty of
// *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// *   GNU Affero General Public License for more details.
// *
// *   You should have received a copy of the GNU Affero General Public License
// *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
// */
//package org.cloud.sonic.agent.tests.android.minicap;
//
//import com.alibaba.fastjson.JSONObject;
//import jakarta.websocket.Session;
//import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
//import org.cloud.sonic.agent.tools.BytesTool;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.util.*;
//import java.util.concurrent.BlockingQueue;
//import java.util.concurrent.atomic.AtomicReference;
//
///**
// * 视频流输出线程
// *
// * @author Eason(main) JayWenStar(until e1a877b7)
// * @date 2021/12/2 12:12 上午
// */
//public class MiniCapOutputSocketThread extends Thread {
//
//    // 日志记录器，用于记录线程运行过程中的信息
//    private final Logger log = LoggerFactory.getLogger(MiniCapOutputSocketThread.class);
//
//    /**
//     * 占用符逻辑参考：{@link AndroidTestTaskBootThread#ANDROID_TEST_TASK_BOOT_PRE}
//     */
//    public final static String ANDROID_OUTPUT_SOCKET_PRE = "android-minicap-output-socket-task-%s-%s-%s";
//
//    // 用于发送图像数据的线程
//    private MiniCapInputSocketThread sendImg;
//    // 用于存储图像头部信息的原子引用
//    private AtomicReference<String[]> banner;
//    // 用于存储图像列表的原子引用
//    private AtomicReference<List<byte[]>> imgList;
//    // WebSocket会话，用于与客户端进行通信
//    private Session session;
//    // 图片质量级别，如 "low", "middle", "fixed", "high"
//    private String pic;
//    // 设备唯一标识符
//    private String udId;
//    // 安卓测试任务启动线程
//    private AndroidTestTaskBootThread androidTestTaskBootThread;
//    // 控制线程是否继续运行的标志
//    private boolean isRunning = true;
//    // 图像列表的最大容量，用于防止内存泄漏
//    private static final int MAX_IMG_LIST_SIZE = 100;
//
//    /**
//     * 构造函数，初始化线程所需的参数
//     *
//     * @param sendImg 用于发送图像数据的线程
//     * @param banner  用于存储图像头部信息的原子引用
//     * @param imgList 用于存储图像列表的原子引用
//     * @param session WebSocket会话
//     * @param pic     图片质量级别
//     */
//    public MiniCapOutputSocketThread(
//            MiniCapInputSocketThread sendImg,
//            AtomicReference<String[]> banner,
//            AtomicReference<List<byte[]>> imgList,
//            Session session,
//            String pic
//    ) {
//        this.sendImg = sendImg;
//        this.banner = banner;
//        this.imgList = imgList;
//        this.session = session;
//        this.pic = pic;
//        this.androidTestTaskBootThread = sendImg.getAndroidTestTaskBootThread();
//
//        // 设置为守护线程，当主线程退出时，该线程也会退出
//        this.setDaemon(true);
//        // 设置线程名称，方便调试和监控
//        this.setName(androidTestTaskBootThread.formatThreadName(ANDROID_OUTPUT_SOCKET_PRE));
//    }
//
//    /**
//     * 检查WebSocket会话是否打开
//     *
//     * @return 如果会话不为空且处于打开状态，返回true；否则返回false
//     */
//    public boolean sessionOpen() {
//        return session != null && session.isOpen();
//    }
//
//    /**
//     * 停止线程的运行
//     */
//    public void stopRunning() {
//        this.isRunning = false;
//        // 中断线程，唤醒可能正在等待的操作
//        this.interrupt();
//    }
//
//    @Override
//    public void run() {
//        // 已读取的头部字节数
//        int readBannerBytes = 0;
//        // 头部长度，初始值为2
//        int bannerLength = 2;
//        // 已读取的帧字节数
//        int readFrameBytes = 0;
//        // 帧体长度
//        int frameBodyLength = 0;
//        // 存储帧体数据的字节数组
//        byte[] frameBody = new byte[0];
//        // 存储上一次发送的图像数据，用于比较
//        byte[] oldBytes = new byte[0];
//        // 计数器，用于控制图像发送频率
//        int count = 0;
//        // 获取发送图像数据线程的数据队列
//        BlockingQueue<byte[]> dataQueue = sendImg.getDataQueue();
//
//        // 当线程处于运行状态且发送图像数据的线程存活时，继续循环
//        while (isRunning && sendImg.isAlive()) {
//            byte[] buffer = new byte[0];
//            try {
//                // 从数据队列中取出数据，如果队列为空，线程会阻塞等待
//                buffer = dataQueue.take();
//            } catch (InterruptedException e) {
//                // 如果线程在等待过程中被中断，检查是否是正常停止
//                if (isRunning) {
//                    log.debug("获取数据流中断：", e);
//                }
//                return;
//            }
//            // 获取缓冲区的长度
//            int len = buffer.length;
//            // 遍历缓冲区中的每个字节
//            for (int cursor = 0; cursor < len; ) {
//                // 获取当前字节的无符号整数值
//                int byte10 = buffer[cursor] & 0xff;
//                if (readBannerBytes < bannerLength) {
//                    // 第一次进来读取头部信息
//                    switch (readBannerBytes) {
//                        case 0:
//                            // 版本信息
//                            banner.get()[0] = buffer[cursor] + "";
//                            break;
//                        case 1:
//                            // 头部长度
//                            bannerLength = buffer[cursor];
//                            banner.get()[1] = String.valueOf(bannerLength);
//                            break;
//                        case 2:
//                        case 3:
//                        case 4:
//                        case 5:
//                            // 某些头部信息，转换为长整型
//                            banner.get()[5] = BytesTool.bytesToLong(buffer, 2) + "";
//                            break;
//                        case 6:
//                        case 7:
//                        case 8:
//                        case 9:
//                            // 某些头部信息，转换为长整型
//                            banner.get()[9] = BytesTool.bytesToLong(buffer, 6) + "";
//                            break;
//                        case 10:
//                        case 11:
//                        case 12:
//                        case 13:
//                            // 某些头部信息，转换为长整型
//                            banner.get()[13] = BytesTool.bytesToLong(buffer, 10) + "";
//                            break;
//                        case 14:
//                        case 15:
//                        case 16:
//                        case 17:
//                            // 某些头部信息，转换为长整型
//                            banner.get()[17] = BytesTool.bytesToLong(buffer, 14) + "";
//                            break;
//                        case 18:
//                        case 19:
//                        case 20:
//                        case 21:
//                            // 某些头部信息，转换为长整型
//                            banner.get()[21] = BytesTool.bytesToLong(buffer, 18) + "";
//                            break;
//                        case 22:
//                            // 某些头部信息，进行简单计算
//                            banner.get()[22] += buffer[cursor] * 90;
//                            break;
//                        case 23:
//                            // 某些头部信息
//                            banner.get()[23] = buffer[cursor] + "";
//                            break;
//                    }
//                    // 移动游标和增加已读取的头部字节数
//                    cursor += 1;
//                    readBannerBytes += 1;
//                    if (readBannerBytes == bannerLength) {
//                        // 头部信息读取完成
//                        log.info("banner读取已就绪");
//                        if (sessionOpen()) {
//                            // 发送头部信息中的图像尺寸信息给客户端
//                            JSONObject size = new JSONObject();
//                            size.put("msg", "size");
//                            size.put("width", banner.get()[9]);
//                            size.put("height", banner.get()[13]);
//                            BytesTool.sendText(session, size.toJSONString());
//                        }
//                    }
//                } else if (readFrameBytes < 4) {
//                    // 读取并设置图片的大小
//                    frameBodyLength += (byte10 << (readFrameBytes * 8));
//                    cursor += 1;
//                    readFrameBytes += 1;
//                } else {
//                    if (len - cursor >= frameBodyLength) {
//                        // 当前缓冲区中的数据足够组成一帧图像
//                        byte[] subByte = BytesTool.subByteArray(buffer, cursor,
//                                cursor + frameBodyLength);
//                        frameBody = BytesTool.addBytes(frameBody, subByte);
//                        // 检查图像数据的起始标志
//                        if ((frameBody[0] != -1) || frameBody[1] != -40) {
//                            return;
//                        }
//                        // 复制帧体数据
//                        final byte[] finalBytes = BytesTool.subByteArray(frameBody,
//                                0, frameBody.length);
//                        if (sessionOpen()) {
//                            // 检查是否与上一次发送的图像数据不同
//                            if (!Arrays.equals(oldBytes, finalBytes)) {
//                                switch (pic) {
//                                    case "low":
//                                        // 低质量模式，计数器加1
//                                        count++;
//                                        break;
//                                    case "middle":
//                                    case "fixed":
//                                        // 中等质量或固定质量模式，计数器加2
//                                        count += 2;
//                                        break;
//                                    case "high":
//                                        // 高质量模式，不改变计数器
//                                        break;
//                                }
//                                if (count % 4 == 0) {
//                                    // 每4次满足条件时发送图像数据
//                                    count = 0;
//                                    oldBytes = finalBytes;
//                                    BytesTool.sendByte(session, finalBytes);
//                                }
//                            }
//                        }
//                        if (imgList != null) {
//                            // 获取图像列表
//                            List<byte[]> list = imgList.get();
//                            if (list.size() >= MAX_IMG_LIST_SIZE) {
//                                // 如果列表达到最大容量，移除最早添加的元素
//                                list.remove(0);
//                            }
//                            // 将当前图像数据添加到列表中
//                            list.add(finalBytes);
//                        }
//                        // 移动游标，重置帧体长度和已读取的帧字节数
//                        cursor += frameBodyLength;
//                        frameBodyLength = 0;
//                        readFrameBytes = 0;
//                        frameBody = new byte[0];
//                    } else {
//                        // 当前缓冲区中的数据不足以组成一帧图像
//                        byte[] subByte = BytesTool.subByteArray(buffer, cursor, len);
//                        frameBody = BytesTool.addBytes(frameBody, subByte);
//                        frameBodyLength -= (len - cursor);
//                        readFrameBytes += (len - cursor);
//                        cursor = len;
//                    }
//                }
//            }
//        }
//    }
//}