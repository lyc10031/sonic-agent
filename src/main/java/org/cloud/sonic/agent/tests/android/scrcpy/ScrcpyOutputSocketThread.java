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
package org.cloud.sonic.agent.tests.android.scrcpy;

import jakarta.websocket.Session;
import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import static org.cloud.sonic.agent.tools.BytesTool.sendByte;

/**
 * 视频流输出线程
 */
public class ScrcpyOutputSocketThread extends Thread {

    private final Logger log = LoggerFactory.getLogger(ScrcpyOutputSocketThread.class);

    public final static String ANDROID_OUTPUT_SOCKET_PRE = "android-scrcpy-output-socket-task-%s-%s-%s";

    private ScrcpyInputSocketThread scrcpyInputSocketThread;

    private Session session;

    private String udId;

    private AndroidTestTaskBootThread androidTestTaskBootThread;

    public ScrcpyOutputSocketThread(
            ScrcpyInputSocketThread scrcpyInputSocketThread,
            Session session
    ) {
        this.scrcpyInputSocketThread = scrcpyInputSocketThread;
        this.session = session;
        this.androidTestTaskBootThread = scrcpyInputSocketThread.getAndroidTestTaskBootThread();
        this.setDaemon(true);
        this.setName(androidTestTaskBootThread.formatThreadName(ANDROID_OUTPUT_SOCKET_PRE));
    }
//
//    @Override
//    public void run() {
//        while (scrcpyInputSocketThread.isAlive()) {
//            BlockingQueue<byte[]> dataQueue = scrcpyInputSocketThread.getDataQueue();
//            byte[] buffer = new byte[0];
//            try {
//                buffer = dataQueue.take();
//            } catch (InterruptedException e) {
//                log.debug("scrcpy was interrupted：", e);
//            }
//            sendByte(session, buffer);
//        }
    //    }
    @Override
    public void run() {
        // 使用批量消费模式提升吞吐量
        List<byte[]> bufferBatch = new ArrayList<>(50); // 预分配批量缓冲区
        while (scrcpyInputSocketThread.isAlive()) {
            BlockingQueue<byte[]> dataQueue = scrcpyInputSocketThread.getDataQueue();
            try {
                // 批量取出队列数据（最多50帧）
                int count = dataQueue.drainTo(bufferBatch, 50);
                if (count > 0) {
                    // 批量发送时需要保持帧顺序
                    for (byte[] frame : bufferBatch) {
                        sendByte(session, frame);
                    }
                    bufferBatch.clear();
                } else {
                    // 队列为空时使用阻塞获取单帧
                    byte[] frame = dataQueue.take();
                    sendByte(session, frame);
                }
            } catch (InterruptedException e) {
                log.debug("scrcpy输出线程被中断", e);
                Thread.currentThread().interrupt(); // 保持中断状态
                break;
            } catch (Exception e) {
                log.error("视频流发送异常: {}", e.getMessage());
            }
        }
        // 添加资源清理逻辑
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (Exception e) {
                log.error("关闭session异常: {}", e.getMessage());
            }
        }
    }
}