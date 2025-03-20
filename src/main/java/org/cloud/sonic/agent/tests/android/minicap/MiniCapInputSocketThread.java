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

import com.android.ddmlib.IDevice;
import jakarta.websocket.Session;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.common.maps.ScreenMap;
import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
import org.cloud.sonic.agent.tools.PortTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;

import static org.cloud.sonic.agent.tools.BytesTool.subByteArray;

/**
 * minicap socket线程
 * 通过端口转发，将设备视频流转发到此Socket
 *
 * @author Eason(main) JayWenStar(until e1a877b7)
 * @date 2021/12/02 00:52 下午
 */
public class MiniCapInputSocketThread extends Thread {

    private final Logger log = LoggerFactory.getLogger(MiniCapInputSocketThread.class);

    /**
     * 占用符逻辑参考：{@link AndroidTestTaskBootThread#ANDROID_TEST_TASK_BOOT_PRE}
     */
    public final static String ANDROID_INPUT_SOCKET_PRE = "android-minicap-input-socket-task-%s-%s-%s";

    private IDevice iDevice;

    private BlockingQueue<byte[]> dataQueue;

    private MiniCapLocalThread miniCapPro;

    private AndroidTestTaskBootThread androidTestTaskBootThread;

    private Session session;

    public MiniCapInputSocketThread(IDevice iDevice, BlockingQueue<byte[]> dataQueue, MiniCapLocalThread miniCapPro, Session session) {
        this.iDevice = iDevice;
        this.dataQueue = dataQueue;
        this.miniCapPro = miniCapPro;
        this.session = session;
        this.androidTestTaskBootThread = miniCapPro.getAndroidTestTaskBootThread();

        // 让资源合理关闭
        this.setDaemon(false);
        this.setName(androidTestTaskBootThread.formatThreadName(ANDROID_INPUT_SOCKET_PRE));
    }

    public IDevice getiDevice() {
        return iDevice;
    }

    public BlockingQueue<byte[]> getDataQueue() {
        return dataQueue;
    }

    public MiniCapLocalThread getMiniCapPro() {
        return miniCapPro;
    }

    public AndroidTestTaskBootThread getAndroidTestTaskBootThread() {
        return androidTestTaskBootThread;
    }

    public Session getSession() {
        return session;
    }

    @Override
    public void run() {

        int finalMiniCapPort = PortTool.getPort();
        AndroidDeviceBridgeTool.forward(iDevice, finalMiniCapPort, "minicap");
        // 优化点1：使用更大缓冲区（8KB）减少读取次数
        final int BUFFER_SIZE = 8192;
        Socket capSocket = null;
        InputStream inputStream = null;

        try {
            capSocket = new Socket("localhost", finalMiniCapPort);
            inputStream = capSocket.getInputStream();
            int len = 1024;

            // 优化点2：使用缓冲流提升读取性能
            inputStream = new BufferedInputStream(capSocket.getInputStream());
            byte[] buffer = new byte[BUFFER_SIZE];

            while (miniCapPro.isAlive()) {

                int realLen = inputStream.read(buffer);
                if (realLen == -1) {
                    log.info("miniCap socket closed.");
                    break;
                }
//                if (buffer.length != realLen && realLen >= 0) {
//                    buffer = subByteArray(buffer, 0, realLen);
//                }
//                if (realLen >= 0) {
//                    dataQueue.offer(buffer);
//                }
                // 优化点3：避免数组拷贝直接使用原数据
                if (realLen == BUFFER_SIZE) {
                    dataQueue.put(buffer.clone());
                } else {
                    dataQueue.put(Arrays.copyOfRange(buffer, 0, realLen));
                }
            }
        } catch (IOException e) {

            // 优化点4：细化异常处理
            if (!capSocket.isClosed()) {
                log.error("视频流读取异常: {}", e.getMessage());
            }
        } catch (InterruptedException e) {
            log.debug("视频流线程正常中断");
            Thread.currentThread().interrupt();
        }finally {
            // 优化点5：统一资源释放逻辑
            closeResources(capSocket, inputStream);
            AndroidDeviceBridgeTool.removeForward(iDevice, finalMiniCapPort, "minicap");
            if (session != null) {
                ScreenMap.getMap().remove(session);
            }
        }
        AndroidDeviceBridgeTool.removeForward(iDevice, finalMiniCapPort, "minicap");
        if (session != null) {
            ScreenMap.getMap().remove(session);
        }
    }

    // 新增辅助方法：统一关闭资源
    private void closeResources(Socket socket, InputStream is) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                log.info("miniCap socket已关闭");
            }
            if (is != null) {
                is.close();
                log.info("输入流已关闭");
            }
            if (miniCapPro.isAlive()) {
                miniCapPro.interrupt();
                log.info("miniCap线程已终止");
            }
        } catch (IOException e) {
            log.warn("资源关闭异常: {}", e.getMessage());
        }
    }
}

