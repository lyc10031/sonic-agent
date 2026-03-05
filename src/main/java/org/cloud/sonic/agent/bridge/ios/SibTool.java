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
package org.cloud.sonic.agent.bridge.ios;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import jakarta.websocket.Session;
import org.cloud.sonic.agent.common.interfaces.DeviceStatus;
import org.cloud.sonic.agent.common.interfaces.PlatformType;
import org.cloud.sonic.agent.common.maps.*;
import org.cloud.sonic.agent.tests.LogUtil;
import org.cloud.sonic.agent.tests.ios.IOSBatteryThread;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.PortTool;
import org.cloud.sonic.agent.tools.ProcessCommandTool;
import org.cloud.sonic.agent.tools.ScheduleTool;
import org.cloud.sonic.agent.transport.TransportWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.cloud.sonic.agent.tools.BytesTool.sendText;

@DependsOn({"iOSThreadPoolInit"})
@Component
@Order(value = Ordered.HIGHEST_PRECEDENCE)
public class SibTool implements ApplicationListener<ContextRefreshedEvent> {
    private static final Logger logger = LoggerFactory.getLogger(SibTool.class);
    @Value("${modules.ios.wda-bundle-id}")
    private String getBundleId;

    @Value("${modules.ios.wda-xcode-project-path:default}")
    private String getXcodeProjectPath;

    @Value("${modules.ios.use-go-ios-tunnel:false}")
    private Boolean getUseGoIosTunnel;

    private static String bundleId;
    private static String xcodeProjectPath;
    private static Boolean useGoIosTunnel;
    private static String sudoPassword;
    private static File sibBinary = new File("plugins" + File.separator + "sonic-ios-bridge");
    private static String sib = sibBinary.getAbsolutePath();
    private static RestTemplate restTemplate;

    @Autowired
    private RestTemplate restTemplateBean;
    private static Map<String, Integer> webViewMap = new HashMap<>();

    // 1. 增加进程状态同步锁（线程安全优化）
    private static final Map<String, ReentrantLock> processLocks = new ConcurrentHashMap<>();

    // 预编译正则，提高解析性能 (ROI: 性能优化)
    private static final java.util.regex.Pattern SERVER_URL_PATTERN =
            java.util.regex.Pattern.compile("ServerURLHere->(http://([^:/]+):(\\d+))");

    @PostConstruct
    public void setEnv() {
        bundleId = getBundleId;
        xcodeProjectPath = getXcodeProjectPath;
        useGoIosTunnel = getUseGoIosTunnel;

        sudoPassword = System.getenv("SUDO_PASSWORD");
        if (sudoPassword == null || sudoPassword.isEmpty()) {
            sudoPassword = System.getProperty("sudo.password", "");
        }

        if (useGoIosTunnel != null && useGoIosTunnel) {
            if (sudoPassword.isEmpty()) {
                logger.warn("go-iOS隧道方案已启用但未设置sudo密码（SUDO_PASSWORD环境变量或sudo.password系统属性），将回退到xcodebuild方案");
                useGoIosTunnel = false;
            } else {
                logger.info("go-iOS隧道方案已启用，sudo密码已配置");
            }
        }
    }

    @Override
    public void onApplicationEvent(@NonNull ContextRefreshedEvent event) {
        init();
        logger.info("Enable iOS Module");
    }

    public void init() {
        restTemplate = restTemplateBean;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("JVM关闭钩子触发，清理所有iOS进程...");
            cleanupAllProcesses();
        }));
        IOSDeviceThreadPool.cachedThreadPool.execute(() -> {
            String processName = "sib";
            if (GlobalProcessMap.getMap().get(processName) != null) {
                Process ps = GlobalProcessMap.getMap().get(processName);
                ps.children().forEach(ProcessHandle::destroy);
                ps.destroy();
            }
            Process listenProcess = null;
            String commandLine = "%s devices listen -d";
            String system = System.getProperty("os.name").toLowerCase();
            try {
                if (system.contains("win")) {
                    listenProcess = Runtime.getRuntime()
                            .exec(new String[]{"cmd", "/c", String.format(commandLine, sib)});
                } else if (system.contains("linux") || system.contains("mac")) {
                    listenProcess = Runtime.getRuntime()
                            .exec(new String[]{"sh", "-c", String.format(commandLine, sib)});
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            InputStreamReader inputStreamReader = new InputStreamReader(listenProcess.getInputStream());
            BufferedReader stdInput = new BufferedReader(inputStreamReader);
            String s;
            while (true) {
                try {
                    if ((s = stdInput.readLine()) == null)
                        break;
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    break;
                }
                JSONObject r = JSONObject.parseObject(s);
                if (r.getString("status").equals("online")) {
                    sendOnlineStatus(r);
                } else if (r.getString("status").equals("offline")) {
                    sendDisConnectStatus(r);
                }
                logger.info(s);
            }
            try {
                stdInput.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                inputStreamReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            logger.info("listen done.");
            GlobalProcessMap.getMap().put(processName, listenProcess);
        });

        ScheduleTool.scheduleAtFixedRate(
                new IOSBatteryThread(),
                IOSBatteryThread.DELAY,
                IOSBatteryThread.DELAY,
                IOSBatteryThread.TIME_UNIT);

        logger.info("iOS devices listening...");
    }

    public static List<String> getDeviceList() {
        List<String> result = new ArrayList<>();
        String commandLine = "%s devices";
        List<String> data = ProcessCommandTool.getProcessLocalCommand(String.format(commandLine, sib));
        for (String a : data) {
            if (a.isEmpty() || a.contains("no device")) {
                break;
            }
            if (a.contains(" ")) {
                result.add(a.substring(0, a.indexOf(" ")));
            }
        }
        return result;
    }

    public static void sendDisConnectStatus(JSONObject jsonObject) {
        if (StringUtils.hasText(jsonObject.getString("serialNumber"))) {
            JSONObject deviceStatus = new JSONObject();
            deviceStatus.put("msg", "deviceDetail");
            deviceStatus.put("udId", jsonObject.getString("serialNumber"));
            deviceStatus.put("status", DeviceStatus.DISCONNECTED);
            deviceStatus.put("platform", PlatformType.IOS);
            logger.info("iOS devices: " + jsonObject.getString("serialNumber") + " OFFLINE!");
            TransportWorker.send(deviceStatus);
            IOSDeviceManagerMap.getMap().remove(jsonObject.getString("serialNumber"));
            DevicesBatteryMap.getTempMap().remove(jsonObject.getString("serialNumber"));
        }
    }

    public static void sendOnlineStatus(JSONObject jsonObject) {
        if (StringUtils.hasText(jsonObject.getString("serialNumber"))) {
            mount(jsonObject.getString("serialNumber"));
            JSONObject detail = jsonObject.getJSONObject("deviceDetail");
            JSONObject deviceStatus = new JSONObject();
            deviceStatus.put("msg", "deviceDetail");
            deviceStatus.put("udId", jsonObject.getString("serialNumber"));
            deviceStatus.put("name", detail.getString("deviceName"));
            deviceStatus.put("model", detail.getString("generationName"));
            deviceStatus.put("status", DeviceStatus.ONLINE);
            deviceStatus.put("platform", PlatformType.IOS);
            deviceStatus.put("version", detail.getString("productVersion"));
            deviceStatus.put("size", getSize(jsonObject.getString("serialNumber")));
            deviceStatus.put("cpu", detail.getString("cpuArchitecture"));
            deviceStatus.put("manufacturer", "APPLE");
            logger.info("iOS Devices: " + jsonObject.getString("serialNumber") + " ONLINE!");
            TransportWorker.send(deviceStatus);
            IOSInfoMap.getDetailMap().put(jsonObject.getString("serialNumber"), detail);
            IOSDeviceManagerMap.getMap().remove(jsonObject.getString("serialNumber"));
            DevicesBatteryMap.getTempMap().remove(jsonObject.getString("serialNumber"));
        }
    }

    public static String getName(String udId) {
        String r = IOSInfoMap.getDetailMap().get(udId).getString("deviceName");
        return r != null ? r : "";
    }

    public static int[] startWda(String udId) throws IOException, InterruptedException {
        Socket wda = PortTool.getBindSocket();
        Socket mjpeg = PortTool.getBindSocket();
        int wdaPort = PortTool.releaseAndGetPort(wda);
        int mjpegPort = PortTool.releaseAndGetPort(mjpeg);
        return startWda(udId, wdaPort, mjpegPort);
    }

    private static int[] startWdaWithGoIos(String udId, int wdaPort, int mjpegPort, boolean skipTunnel) throws IOException, InterruptedException {
        logger.info("[{}] 使用go-iOS隧道方案启动WDA{}", udId, skipTunnel ? " (跳过tunnel启动)" : "");
        List<Process> processList = new ArrayList<>();
        String sudoPassword = SibTool.sudoPassword;
        if (sudoPassword == null || sudoPassword.isEmpty()) {
            logger.error("[{}] 无法获取sudo密码，请设置环境变量SUDO_PASSWORD或JVM参数-Dsudo.password", udId);
            throw new IOException("无法获取sudo密码");
        }
        if (skipTunnel) {
            if (!isGoIosTunnelActive(udId)) {
                logger.warn("[{}] Tunnel未激活，无法跳过启动", udId);
                throw new IOException("Tunnel未激活");
            }
            logger.info("[{}] 使用现有go-iOS隧道", udId);
        } else {
            // 步骤1: 启动隧道
            logger.info("[{}] 步骤1: 启动go-iOS隧道...", udId);

            // 先停止可能残留的隧道进程（避免端口占用错误）
            try {
                Process stopProcess = Runtime.getRuntime().exec("ios tunnel stop 2>/dev/null");
                stopProcess.waitFor(2, TimeUnit.SECONDS);
                logger.debug("[{}] 清理残留隧道完成", udId);
            } catch (Exception e) {
                logger.debug("[{}] 清理残留隧道失败（可能没有残留）: {}", udId, e.getMessage());
            }

            String tunnelCmd = String.format("echo '%s' | sudo -S ios tunnel start --udid=%s 2>&1", sudoPassword, udId);
            Process tunnelProcess = Runtime.getRuntime().exec(new String[]{"sh", "-c", tunnelCmd});
            processList.add(tunnelProcess);

            Thread tunnelLogThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(tunnelProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.info("[{}] Tunnel输出: {}", udId, line);
                    }
                } catch (IOException e) {
                    logger.error("[{}] Tunnel日志读取失败: {}", udId, e.getMessage());
                }
            });
            tunnelLogThread.start();

            Thread.sleep(5000);

            if (!tunnelProcess.isAlive() && tunnelProcess.exitValue() != 0) {
                logger.error("[{}] go-iOS隧道启动失败，退出码: {}", udId, tunnelProcess.exitValue());
                return new int[]{0, 0};
            }

            logger.info("[{}] go-iOS隧道启动成功", udId);
        }

        // 步骤2: 启动WDA
        String runwdaCmd = String.format(
            "ios runwda --bundleid=%s --testrunnerbundleid=%s --xctestconfig=WebDriverAgentRunner.xctest --udid=%s",
            bundleId, bundleId, udId
        );

        logger.info("[{}] 步骤2: 执行go-iOS命令: {}", udId, runwdaCmd);

        Process wdaProcess = Runtime.getRuntime().exec(new String[]{"sh", "-c", runwdaCmd});
        processList.add(wdaProcess);

        Semaphore isFinish = new Semaphore(0);

        InputStreamReader errorStreamReader = new InputStreamReader(wdaProcess.getErrorStream());
        BufferedReader stdError = new BufferedReader(errorStreamReader);
        Thread errorThread = new Thread(() -> {
            String errLine;
            try {
                while ((errLine = stdError.readLine()) != null) {
                    logger.error("[{}] WDA错误输出: {}", udId, errLine);
                    extractIpFromLog(udId, errLine);
                    if (errLine.contains("ServerURLHere") || errLine.contains("Started session successfully") || errLine.contains("\"authorized\":true")) {
                        logger.info("[{}] 从错误流检测到WDA服务启动信号", udId);
                        isFinish.release();
                    }
                }
            } catch (IOException e) {
                logger.error("[{}] 读取错误流异常: {}", udId, e.getMessage());
            }
        });
        errorThread.start();

        InputStreamReader inputStreamReader = new InputStreamReader(wdaProcess.getInputStream());
        BufferedReader stdInput = new BufferedReader(inputStreamReader);

        Thread wdaThread = new Thread(() -> {
            String s;
            while (true) {
                try {
                    if ((s = stdInput.readLine()) == null)
                        break;
                } catch (IOException e) {
                    logger.error("[{}] 读取WDA输出流异常: {}", udId, e.getMessage());
                    break;
                }
                logger.info("[WDA] {}", s);
                extractIpFromLog(udId, s);
                if (s.contains("ServerURLHere") || s.contains("Started session successfully") || s.contains("\"authorized\":true")) {
                    logger.info("[{}] 检测到WDA服务启动信号", udId);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    isFinish.release();
                    // 优化：不在这里 break，继续监听后续可能的 ServerURLHere 信号以提取 IP
                }
            }
            try {
                stdInput.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                inputStreamReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            logger.info("WebDriverAgent print thread shutdown.");
        });
        wdaThread.start();

        int wait = 0;
        while (!isFinish.tryAcquire()) {
            Thread.sleep(500);
            wait++;
            if (wait >= 120) {
                logger.error("[{}] WDA启动超时！已等待{}秒", udId, wait / 2);
                if (wdaProcess != null) {
                    logger.error("[{}] WDA进程存活状态: {}", udId, wdaProcess.isAlive());
                }
                return new int[]{0, 0};
            }
        }
        logger.info("[{}] WDA启动成功，耗时{}秒", udId, wait / 2);

        // ROI 最佳方案：主动探测 IP
        updateIpViaWdaStatus(udId, wdaPort);

        Thread.sleep(5000);

        logger.info("[{}] 启动端口转发...", udId);

        Process forwardWdaProcess = startForwardWithRetry(udId, wdaPort, 8100, "WDA");
        if (forwardWdaProcess == null) {
            logger.error("[{}] WDA端口转发启动失败", udId);
            stopWda(udId);
            return new int[]{0, 0};
        }
        processList.add(forwardWdaProcess);

        Process forwardMjpegProcess = startForwardWithRetry(udId, mjpegPort, 9100, "MJPEG");
        if (forwardMjpegProcess == null) {
            logger.error("[{}] MJPEG端口转发启动失败", udId);
            stopWda(udId);
            return new int[]{0, 0};
        }
        processList.add(forwardMjpegProcess);

        Thread.sleep(2000);

        logger.info("[{}] 端口转发进程状态: WDAForward={}, MJPEGForward={}",
            udId, forwardWdaProcess.isAlive(), forwardMjpegProcess.isAlive());

        IOSProcessMap.getMap().put(udId, processList);

        List<Process> finalProcessList = processList;
        IOSDeviceThreadPool.cachedThreadPool.execute(() -> {
            logger.info("[{}] 启动WDA守护监控线程", udId);

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // go-iOS 方案: tunnel 会正常退出，只监控核心进程（WDA + 2个forward）
                    boolean needRestart = finalProcessList.stream().skip(1).anyMatch(p -> !p.isAlive());
                    logger.debug("[{}] 进程存活检查结果: needRestart={}", udId, needRestart);

                    if (needRestart) {
                        logger.warn("[{}] 检测到进程异常退出，存活状态: {}",
                            udId, finalProcessList.stream().map(Process::isAlive).collect(Collectors.toList()));
                        synchronized (IOSProcessMap.getMap()) {
                            if (IOSProcessMap.getMap().containsKey(udId)) {
                                logger.warn("[{}] WDA进程异常退出，尝试重启...", udId);
                                try {
                                    stopWda(udId);
                                    // go-iOS模式: 直接调用 startWdaWithGoIos 并跳过 tunnel 启动
                                    startWdaWithGoIos(udId, wdaPort, mjpegPort, true);
                                } catch (Exception e) {
                                    logger.error("[{}] WDA重启失败: {}", udId, e.getMessage());
                                }
                            }
                        }
                        break;
                    }

                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    logger.info("[{}] WDA监控线程被中断", udId);
                    break;
                } catch (Exception e) {
                    logger.error("[{}] WDA监控线程异常: {}", udId, e.getMessage());
                    break;
                }
            }
        });

        return new int[]{wdaPort, mjpegPort};
    }

    public static int[] startWda(String udId, int wdaPort, int mjpegPort) throws IOException, InterruptedException {
        ReentrantLock lock = processLocks.computeIfAbsent(udId, k -> new ReentrantLock());
        lock.lock();
        try {
            logger.info("[{}] 开始启动WDA，目标端口 wdaPort={}, mjpegPort={}", udId, wdaPort, mjpegPort);

            if (IOSProcessMap.getMap().containsKey(udId) && isWdaAlive(udId)) {
                logger.info("[{}] WDA already running, reuse existing process", udId);
                // 即使是复用，也尝试探测一次 IP (解决已连接设备 IP 为 null 的问题)
                updateIpViaWdaStatus(udId, wdaPort);
                return new int[]{wdaPort, mjpegPort};
            }

            List<Process> processList;
            if (IOSProcessMap.getMap().get(udId) != null) {
                processList = IOSProcessMap.getMap().get(udId);
                for (Process p : processList) {
                    if (p != null) {
                        p.children().forEach(ProcessHandle::destroy);
                        p.destroy();
                    }
                }
            }
            wdaPort = (wdaPort == 0) ? PortTool.getPort() : wdaPort;
            mjpegPort = (mjpegPort == 0) ? PortTool.getPort() : mjpegPort;
            Process wdaProcess = null;
            final Process[] iProxyProcess = {null};
            String commandLine;

            // ios17 support, but mac only
            if (isUpperThanIos17(udId)) {
                if (useGoIosTunnel != null && useGoIosTunnel) {
                    return startWdaWithGoIos(udId, wdaPort, mjpegPort, false);
                }

                // 增强iOS17+设备的路径校验
                File xcodeProj = new File(xcodeProjectPath);
                logger.info("[{}] 正在验证Xcode项目路径：{}", udId, xcodeProj.getAbsolutePath());

                // 增强路径格式兼容性
                if (xcodeProj.isDirectory()) {
                    xcodeProj = new File(xcodeProj, "WebDriverAgent.xcodeproj");
                    logger.info("[{}] 自动补全项目文件路径：{}", udId, xcodeProj.getAbsolutePath());
                }

                if (!xcodeProj.exists()) {
                    logger.error("[{}] 无效的Xcode项目路径！请检查以下路径是否存在：{}", udId, xcodeProj.getAbsolutePath());
                    return new int[]{0, 0};
                }

                commandLine = String.format(
                    "xcodebuild -project \"%s\" -scheme WebDriverAgentRunner -destination 'id=%s' test",
                    xcodeProj.getAbsolutePath(), // 使用带引号的路径防止空格问题
                    udId);
            } else {
                commandLine = String.format(
                        "%s run wda -u %s -b %s --mjpeg-remote-port 9100 --server-remote-port 8100 --mjpeg-local-port %d --server-local-port %d",
                        sib, udId, bundleId, mjpegPort, wdaPort);
            }
            // 添加完整命令日志
            logger.info("[{}] 执行WDA启动命令: {}", udId, commandLine);

            String system = System.getProperty("os.name").toLowerCase();
            if (system.contains("win")) {
                wdaProcess = Runtime.getRuntime().exec(new String[]{"cmd", "/c", commandLine});
            } else if (system.contains("linux") || system.contains("mac")) {
                wdaProcess = Runtime.getRuntime().exec(new String[]{"sh", "-c", commandLine});
                logger.debug("[{}] Unix-like环境进程已创建", udId);

            }
            // 在进程创建后增加错误流处理（关键补充）
            InputStreamReader errorStreamReader = new InputStreamReader(wdaProcess.getErrorStream());
            BufferedReader stdError = new BufferedReader(errorStreamReader);
            Thread errorThread = new Thread(() -> {
                String errLine;
                try {
                    while ((errLine = stdError.readLine()) != null) {
                        logger.error("[{}] WDA错误输出: {}", udId, errLine);
                        extractIpFromLog(udId, errLine);
                    }
                } catch (IOException e) {
                    logger.error("[{}] 读取错误流异常: {}", udId, e.getMessage());
                }
            });
            errorThread.start();

            InputStreamReader inputStreamReader = new InputStreamReader(wdaProcess.getInputStream());
            BufferedReader stdInput = new BufferedReader(inputStreamReader);
            Semaphore isFinish = new Semaphore(0);

            int finalWdaPort = wdaPort;
            int finalMjpegPort = mjpegPort;

            Thread wdaThread = new Thread(() -> {
                String s;
                while (true) {
                    try {
                        if ((s = stdInput.readLine()) == null)
                            break;
                    } catch (IOException e) {
                        logger.error("[{}] 读取WDA输出流异常: {}", udId, e.getMessage());
                        break;
                    }
                    logger.info("[WDA] {}", s);  // 添加WDA原始输出日志标签
                    extractIpFromLog(udId, s);
                    if (s.contains("ServerURLHere->")) {
                        logger.info("[{}] 检测到WDA服务启动信号", udId);
                        if (SibTool.isUpperThanIos17(udId)) {
                            try {
                                String iproxyCmd = String.format("iproxy -u %s %d:8100 %d:9100 -s 0.0.0.0",
                                        udId, finalWdaPort, finalMjpegPort);
                                logger.info("[{}] 启动iOS17+的iproxy: {}", udId, iproxyCmd);
                                iProxyProcess[0] = Runtime.getRuntime().exec(
                                        new String[]{"sh", "-c", String.format("iproxy -u %s %d:8100 %d:9100 -s 0.0.0.0",
                                                udId, finalWdaPort, finalMjpegPort)});
                            } catch (IOException e) {
                                logger.error("[{}] 创建iproxy进程失败: {}", udId, e.getMessage());

                            }
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        isFinish.release();
                    }
                }
                try {
                    stdInput.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    inputStreamReader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                logger.info("WebDriverAgent print thread shutdown.");
            });
            wdaThread.start();
            int wait = 0;
            while (!isFinish.tryAcquire()) {
                Thread.sleep(500);
                wait++;
                if (wait >= 120) {
                    logger.error("[{}] WDA启动超时！已等待{}秒", udId, wait/2);
                    if (wdaProcess != null) {
                        logger.error("[{}] WDA进程存活状态: {}", udId, wdaProcess.isAlive());
                    }
                    return new int[]{0, 0};

                }
            }
            logger.info("[{}] WDA启动成功，耗时{}秒", udId, wait / 2);

            // ROI 最佳方案：主动探测 IP
            updateIpViaWdaStatus(udId, wdaPort);

            processList = new ArrayList<>();
            processList.add(wdaProcess);
            if (iProxyProcess[0] != null) {
                processList.add(iProxyProcess[0]);
            }
            IOSProcessMap.getMap().put(udId, processList);


            // 新增：启动守护线程监控WDA进程
            List<Process> finalProcessList = processList;
            // 修改原有的守护线程逻辑
            IOSDeviceThreadPool.cachedThreadPool.execute(() -> {
                logger.info("[{}] 启动WDA守护监控线程", udId);

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        // 检查主进程状态
                        boolean needRestart = finalProcessList.stream()
                                .anyMatch(p -> !p.isAlive());
                        logger.debug("[{}] 进程存活检查结果: needRestart={}", udId, needRestart);


                        // 进程被主动停止时会移除映射，监控线程自动退出
                        if (needRestart) {
                            // 添加同步锁防止并发操作
                            logger.warn("[{}] 检测到WDA进程异常退出，存活状态: {}",
                                    udId, finalProcessList.stream().map(p -> p.isAlive()).collect(Collectors.toList()));
                            synchronized (IOSProcessMap.getMap()) {
                                // 再次验证映射关系
                                if (IOSProcessMap.getMap().containsKey(udId)) {
                                    logger.warn("[{}] WDA进程异常退出，尝试重启...", udId);
                                    try {
                                        // 先清理旧进程再启动
                                        stopWda(udId);
                                        startWda(udId, finalWdaPort, finalMjpegPort);
                                    } catch (Exception e) {
                                        logger.error("[{}] WDA重启失败: {}", udId, e.getMessage());
                                    }
                                }
                            }
                            break;
                        }

                        // 每5秒检查一次
                        Thread.sleep(5000);
                    }catch (InterruptedException e) { // 捕获中断异常
                        logger.info("[{}] WDA监控线程被中断", udId);
                        break;
                    } catch (Exception e) {
                        logger.error("[{}] WDA监控线程异常: {}", udId, e.getMessage());
                        logger.error("[{}] 守护线程发生未预期异常: {}", udId, e.getMessage());

                        break;
                    }
                }
            });

            return new int[]{wdaPort, mjpegPort};
        } finally {
            lock.unlock();
        }
    }

private static boolean isWdaAlive(String udId) {
        return IOSProcessMap.getMap().getOrDefault(udId, Collections.emptyList())
            .stream().anyMatch(Process::isAlive);
    }

    private static Process startForwardWithRetry(String udId, int localPort, int devicePort, String name) {
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            try {
                logger.info("[{}] 启动{}端口转发 (尝试 {}/{}): {} -> {}", udId, name, i + 1, maxRetries, localPort, devicePort);
                String forwardCmd = String.format("ios forward --udid=%s %d %d 2>&1", udId, localPort, devicePort);
                Process forwardProcess = Runtime.getRuntime().exec(new String[]{"sh", "-c", forwardCmd});

                Thread logThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(forwardProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            logger.info("[{}] Forward{}输出: {}", udId, name, line);
                        }
                    } catch (IOException e) {
                        logger.error("[{}] Forward{}日志读取失败: {}", udId, name, e.getMessage());
                    }
                });
                logThread.start();

                Thread.sleep(2000);

                if (forwardProcess.isAlive()) {
                    logger.info("[{}] {}端口转发启动成功", udId, name);
                    return forwardProcess;
                } else {
                    logger.warn("[{}] {}端口转发进程已退出，等待重试...", udId, name);
                    Thread.sleep(3000);
                }
            } catch (Exception e) {
                logger.error("[{}] 启动{}端口转发失败: {}", udId, name, e.getMessage());
            }
        }
        return null;
    }

    private static boolean isGoIosTunnelActive(String udId) {
        try {
            Process checkProcess = Runtime.getRuntime().exec(new String[]{"sh", "-c", "ios tunnel ls"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(checkProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(udId)) {
                        return true;
                    }
                }
            }
            checkProcess.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("[{}] 检查go-iOS隧道状态失败: {}", udId, e.getMessage());
        }
        return false;
    }

    // 4. 优化资源回收（内存泄漏修复）
    public static void stopWda(String udId) {
        ReentrantLock lock = processLocks.get(udId);
        if (lock != null) {
            lock.lock();
            try {
                if (IOSProcessMap.getMap().containsKey(udId)) {
                    // 增加流关闭操作（关键资源释放）
                    IOSProcessMap.getMap().get(udId).forEach(p -> {
                        closeProcessResources(p); // 新增资源关闭方法
                        if (p.isAlive()) {
                            p.destroyForcibly();
                            // 新增终止确认逻辑
                            try {
                                if (p.waitFor(3, TimeUnit.SECONDS)) {
                                    logger.info("[{}] 进程正常退出", udId);
                                } else {
                                    logger.warn("[{}] 进程强制终止超时", udId);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
                    IOSProcessMap.getMap().remove(udId);
                    logger.info("[{}] WDA进程已停止", udId);
                }
            } finally {
                lock.unlock();
                processLocks.remove(udId); // 清理锁资源
            }
        }
    }
    // 5. 新增公共方法关闭进程资源（减少代码重复）
    private static void cleanupAllProcesses() {
        logger.info("开始清理所有iOS相关进程...");

        IOSProcessMap.getMap().keySet().forEach(udId -> {
            try {
                stopWda(udId);
                logger.info("[{}] WDA进程已清理", udId);
            } catch (Exception e) {
                logger.error("[{}] 清理WDA进程失败: {}", udId, e.getMessage());
            }
        });

        if (useGoIosTunnel != null && useGoIosTunnel) {
            try {
                Process cleanupProcess = Runtime.getRuntime().exec(new String[]{"sh", "-c", "pkill -f 'ios tunnel start'"});
                cleanupProcess.waitFor(2, TimeUnit.SECONDS);
                logger.info("go-iOS隧道进程已清理");
            } catch (Exception e) {
                logger.warn("清理go-iOS隧道进程失败: {}", e.getMessage());
            }
        }

        logger.info("所有iOS进程清理完成");
    }

    private static void closeProcessResources(Process p) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                if (p.getInputStream() != null) p.getInputStream().close();
                if (p.getErrorStream() != null) p.getErrorStream().close();
                if (p.getOutputStream() != null) p.getOutputStream().close();
                return;
            } catch (IOException e) {
                if (i == maxRetries - 1) {
                    logger.warn("关闭进程资源失败: {}", e.getMessage());
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public static void reboot(String udId) {
        String commandLine = "%s reboot -u %s";
        ProcessCommandTool.getProcessLocalCommand(String.format(commandLine, sib, udId));
    }

    public static void shutdown(String udId) {
        String commandLine = "%s reboot -u %s -s";
        ProcessCommandTool.getProcessLocalCommand(String.format(commandLine, sib, udId));
    }

    public static void install(String udId, String path) {
        String commandLine;
        if (isUpperThanIos17(udId)) {
            commandLine = String.format("ideviceinstaller -u %s -i %s", udId, path);
        } else {
            commandLine = String.format("%s app install -u %s -p %s", sib, udId, path);
        }
        ProcessCommandTool.getProcessLocalCommand(commandLine);
    }

    public static void stopSysLog(String udId) {
        String processName = String.format("process-%s-syslog", udId);
        if (GlobalProcessMap.getMap().get(processName) != null) {
            Process ps = GlobalProcessMap.getMap().get(processName);
            ps.children().forEach(ProcessHandle::destroy);
            ps.destroy();
        }
    }

    public static void getSysLog(String udId, String filter, Session session) {
        new Thread(() -> {
            stopSysLog(udId);
            String system = System.getProperty("os.name").toLowerCase();
            Process ps = null;
            String commandLine = "%s syslog -u %s";
            if (filter != null && filter.length() > 0) {
                commandLine += String.format(" -f %s", filter);
            }
            try {
                if (system.contains("win")) {
                    ps = Runtime.getRuntime().exec(new String[]{"cmd", "/c", String.format(commandLine, sib, udId)});
                } else if (system.contains("linux") || system.contains("mac")) {
                    ps = Runtime.getRuntime().exec(new String[]{"sh", "-c", String.format(commandLine, sib, udId)});
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            String processName = String.format("process-%s-syslog", udId);
            GlobalProcessMap.getMap().put(processName, ps);
            InputStreamReader inputStreamReader = new InputStreamReader(ps.getInputStream());
            BufferedReader stdInput = new BufferedReader(inputStreamReader);
            String s;
            while (true) {
                try {
                    if ((s = stdInput.readLine()) == null)
                        break;
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    break;
                }
                logger.info(s);
                JSONObject appList = new JSONObject();
                appList.put("msg", "logDetail");
                appList.put("detail", s);
                sendText(session, appList.toJSONString());
            }
            try {
                stdInput.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                inputStreamReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            logger.info("sys done.");
        }).start();
    }

    public static void stopOrientationWatcher(String udId) {
        String processName = String.format("process-%s-orientation", udId);
        if (GlobalProcessMap.getMap().get(processName) != null) {
            Process ps = GlobalProcessMap.getMap().get(processName);
            ps.children().forEach(ProcessHandle::destroy);
            ps.destroy();
        }
    }

    public static void orientationWatcher(String udId, Session session) {
        new Thread(() -> {
            String system = System.getProperty("os.name").toLowerCase();
            Process ps = null;
            String commandLine = "%s orientation -w -u %s";
            try {
                if (system.contains("win")) {
                    ps = Runtime.getRuntime().exec(new String[]{"cmd", "/c", String.format(commandLine, sib, udId)});
                } else if (system.contains("linux") || system.contains("mac")) {
                    ps = Runtime.getRuntime().exec(new String[]{"sh", "-c", String.format(commandLine, sib, udId)});
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            String processName = String.format("process-%s-orientation", udId);
            GlobalProcessMap.getMap().put(processName, ps);
            InputStreamReader inputStreamReader = new InputStreamReader(ps.getInputStream());
            BufferedReader stdInput = new BufferedReader(inputStreamReader);
            String s;
            while (true) {
                try {
                    if ((s = stdInput.readLine()) == null)
                        break;
                    // 增加会话状态检查
                    if (!session.isOpen()) {
                        logger.warn("[{}] Session已关闭，终止方向监听", udId);
                        break;
                    }

                    logger.info(s);
                    if (s.contains("orientation") && (!s.contains("0")) && (!s.contains("failed"))) {
                        int result = switch (BytesTool.getInt(s)) {
                            case 2 -> 180;
                            case 3 -> 270;
                            case 4 -> 90;
                            default -> 0;
                        };
                        JSONObject rotation = new JSONObject();
                        rotation.put("msg", "rotation");
                        rotation.put("value", result);
                        try {
                            sendText(session, rotation.toJSONString());
                        } catch (IllegalStateException e) {
                            logger.error("[{}] 发送旋转数据失败: {}", udId, e.getMessage());
                            break;
                        }
                    }
                } catch (Exception e) { // 扩大异常捕获范围
                    logger.error("[{}] 方向监听异常: {}", udId, e.getMessage());
                    break;
                }
            }
            // 增加资源清理
            try {
                if (session != null && session.isOpen()) {
                    session.close();
                }
            } catch (IOException e) {
                logger.error("[{}] 关闭会话异常: {}", udId, e.getMessage());
            }

            try {
                stdInput.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                inputStreamReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            logger.info("orientation watcher done.");
        }).start();
    }

    public static List<String> getAppList(String udId) {
        return getAppList(udId, null).stream().map(e -> e.getString("bundleId")).collect(Collectors.toList());
    }

    public static List<JSONObject> getAppList(String udId, Session session) {
        List<JSONObject> result = new ArrayList<>();
        Process appListProcess = null;
        String commandLine = "%s app list -u %s -j -i";
        String system = System.getProperty("os.name").toLowerCase();
        try {
            if (system.contains("win")) {
                appListProcess = Runtime.getRuntime()
                        .exec(new String[]{"cmd", "/c", String.format(commandLine, sib, udId)});
            } else if (system.contains("linux") || system.contains("mac")) {
                appListProcess = Runtime.getRuntime()
                        .exec(new String[]{"sh", "-c", String.format(commandLine, sib, udId)});
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        InputStreamReader inputStreamReader = new InputStreamReader(appListProcess.getInputStream());
        BufferedReader stdInput = new BufferedReader(inputStreamReader);
        String s;
        while (true) {
            try {
                if (StringUtils.isEmpty(s = stdInput.readLine()))
                    break;
            } catch (IOException e) {
                logger.info(e.getMessage());
                break;
            }
            try {
                JSONObject appInfo = JSON.parseObject(s);
                if (session != null) {
                    JSONObject appList = new JSONObject();
                    appList.put("msg", "appListDetail");
                    appList.put("detail", appInfo);
                    sendText(session, appList.toJSONString());
                } else {
                    result.add(appInfo);
                }
            } catch (JSONException e) {
                logger.info(e.fillInStackTrace().toString());
            }
        }
        try {
            stdInput.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            inputStreamReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        logger.info("app list done.");
        return result;
    }

    /**
     * 获取指定 App 的版本信息
     * 优先获取市场版本号 (如 3.33.0)，其次获取构建版本号 (如 19)
     *
     * @param udId     设备UDID
     * @param bundleId 应用包名
     * @return 版本号字符串
     */
    public static String getAppVersion(String udId, String bundleId) {
        List<JSONObject> apps = getAppList(udId, null);
        for (JSONObject app : apps) {
            if (bundleId.equals(app.getString("bundleId"))) {
                // 1. 优先取市场版本号 (Short Version String)，这通常是 3.33.0 这种格式
                String shortVersion = app.getString("shortVersion");
                if (StringUtils.hasText(shortVersion)) {
                    return shortVersion;
                }
                
                // 2. 兼容性字段检测
                String versionName = app.getString("versionName");
                if (StringUtils.hasText(versionName)) {
                    return versionName;
                }

                // 3. 最后退而求其次取构建版本号 (Build Number)，通常是 19 这种格式
                return app.getString("version") != null ? app.getString("version") : "";
            }
        }
        return "";
    }

    public static void getProcessList(String udId, Session session) {
        Process appProcess = null;
        String commandLine = "%s ps -u %s -j";
        String system = System.getProperty("os.name").toLowerCase();
        try {
            if (system.contains("win")) {
                appProcess = Runtime.getRuntime()
                        .exec(new String[]{"cmd", "/c", String.format(commandLine, sib, udId)});
            } else if (system.contains("linux") || system.contains("mac")) {
                appProcess = Runtime.getRuntime()
                        .exec(new String[]{"sh", "-c", String.format(commandLine, sib, udId)});
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        InputStreamReader inputStreamReader = new InputStreamReader(appProcess.getInputStream());
        BufferedReader stdInput = new BufferedReader(inputStreamReader);
        String s;
        while (true) {
            try {
                if ((s = stdInput.readLine()) == null)
                    break;
            } catch (IOException e) {
                logger.info(e.getMessage());
                break;
            }
            List<JSONObject> pList = JSON.parseArray(s, JSONObject.class);
            for (JSONObject p : pList) {
                JSONObject processListDetail = new JSONObject();
                processListDetail.put("msg", "processListDetail");
                processListDetail.put("detail", p);
                sendText(session, processListDetail.toJSONString());
            }
        }
        try {
            stdInput.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            inputStreamReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        logger.info("process done.");
    }

    public static void locationUnset(String udId) {
        String commandLine = "%s location unset -u %s";
        ProcessCommandTool.getProcessLocalCommand(String.format(commandLine, sib, udId));
    }

    public static void locationSet(String udId, String longitude, String latitude) {
        String commandLine = "%s location set -u %s --long %s --lat %s";
        ProcessCommandTool.getProcessLocalCommand(String.format(commandLine, sib, udId, longitude, latitude));
    }

    public static JSONObject getBattery(String udId) {
        String commandLine = "%s battery -u %s -j";
        String res = ProcessCommandTool.getProcessLocalCommandStr(commandLine.formatted(sib, udId));
        return JSONObject.parseObject(res, JSONObject.class);
    }

    public static void launch(String udId, String pkg) {
        String commandLine = "%s app launch -u %s -b %s";
        ProcessCommandTool.getProcessLocalCommand(String.format(commandLine, sib, udId, pkg));
    }

    public static void kill(String udId, String pkg) {
        String commandLine = "%s app kill -u %s -b %s";
        ProcessCommandTool.getProcessLocalCommand(String.format(commandLine, sib, udId, pkg));
    }

    public static void uninstall(String udId, String pkg) {
        String commandLine;
        if (isUpperThanIos17(udId)) {
            commandLine = String.format("ideviceinstaller -u %s -U %s", udId, pkg);
        } else {
            commandLine = String.format("%s app uninstall -u %s -b %s", sib, udId, pkg);
        }
        ProcessCommandTool.getProcessLocalCommand(commandLine);
    }

    public static int battery(String udId) {
        String commandLine = "%s battery -u %s -j";
        String re = ProcessCommandTool.getProcessLocalCommandStr(String.format(commandLine, sib, udId));
        return JSON.parseObject(re).getInteger("CurrentCapacity");
    }

    public static void stopWebInspector(String udId) {
        String processName = String.format("process-%s-web-inspector", udId);
        if (GlobalProcessMap.getMap().get(processName) != null) {
            Process ps = GlobalProcessMap.getMap().get(processName);
            ps.children().forEach(ProcessHandle::destroy);
            ps.destroy();
        }
    }

    public static int startWebInspector(String udId) {
        Process ps = null;
        String commandLine = "%s webinspector -u %s -p %d --cdp";
        int port = PortTool.getPort();
        try {
            String system = System.getProperty("os.name").toLowerCase();
            if (system.contains("win")) {
                ps = Runtime.getRuntime()
                        .exec(new String[]{"cmd", "/c", String.format(commandLine, sib, udId, port)});
            } else if (system.contains("linux") || system.contains("mac")) {
                ps = Runtime.getRuntime()
                        .exec(new String[]{"sh", "-c", String.format(commandLine, sib, udId, port)});
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        InputStreamReader inputStreamReader = new InputStreamReader(ps.getInputStream());
        BufferedReader stdInput = new BufferedReader(inputStreamReader);
        InputStreamReader err = new InputStreamReader(ps.getErrorStream());
        BufferedReader stdInputErr = new BufferedReader(err);
        Semaphore isFinish = new Semaphore(0);
        Thread webErr = new Thread(() -> {
            String s;
            while (true) {
                try {
                    if ((s = stdInputErr.readLine()) == null)
                        break;
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    break;
                }
                if (!s.equals("close send protocol")) {
                    logger.info(s);
                }
            }
            try {
                stdInputErr.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                err.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            logger.info("WebInspector print thread shutdown.");
        });
        webErr.start();
        Thread web = new Thread(() -> {
            String s;
            while (true) {
                try {
                    if ((s = stdInput.readLine()) == null)
                        break;
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    break;
                }
                logger.info(s);
                if (s.contains("service started successfully")) {
                    isFinish.release();
                }
            }
            try {
                stdInput.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                inputStreamReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            webViewMap.remove(udId);
            logger.info("WebInspector print thread shutdown.");
        });
        web.start();
        int wait = 0;
        while (!isFinish.tryAcquire()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            wait++;
            if (wait >= 120) {
                logger.info(udId + " WebInspector start timeout!");
                return 0;
            }
        }
        String processName = String.format("process-%s-web-inspector", udId);
        GlobalProcessMap.getMap().put(processName, ps);
        return port;
    }

    public static void stopProxy(String udId, int target) {
        String processName = String.format("process-%s-proxy-%d", udId, target);
        if (GlobalProcessMap.getMap().get(processName) != null) {
            Process ps = GlobalProcessMap.getMap().get(processName);
            ps.children().forEach(ProcessHandle::destroy);
            ps.destroy();
        }
    }

    public static void proxy(String udId, int local, int target) {
        stopProxy(udId, target);
        Process ps = null;
        String commandLine = "%s proxy -u %s -l %d -r %d";
        try {
            String system = System.getProperty("os.name").toLowerCase();
            if (system.contains("win")) {
                ps = Runtime.getRuntime()
                        .exec(new String[]{"cmd", "/c", String.format(commandLine, sib, udId, local, target)});
            } else if (system.contains("linux") || system.contains("mac")) {
                ps = Runtime.getRuntime()
                        .exec(new String[]{"sh", "-c", String.format(commandLine, sib, udId, local, target)});
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        InputStreamReader inputStreamReader = new InputStreamReader(ps.getInputStream());
        BufferedReader stdInput = new BufferedReader(inputStreamReader);
        InputStreamReader err = new InputStreamReader(ps.getErrorStream());
        BufferedReader stdInputErr = new BufferedReader(err);
        Thread proErr = new Thread(() -> {
            String s;
            while (true) {
                try {
                    if ((s = stdInputErr.readLine()) == null)
                        break;
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    break;
                }
                logger.info(s);
            }
            try {
                stdInputErr.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                err.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            logger.info("proxy print thread shutdown.");
        });
        proErr.start();
        Thread pro = new Thread(() -> {
            String s;
            while (true) {
                try {
                    if ((s = stdInput.readLine()) == null)
                        break;
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    break;
                }
                logger.info(s);
            }
            try {
                stdInput.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                inputStreamReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            logger.info("proxy print thread shutdown.");
        });
        pro.start();
        String processName = String.format("process-%s-proxy-%d", udId, target);
        GlobalProcessMap.getMap().put(processName, ps);
    }

    public static int getOrientation(String udId) {
        String commandLine = "%s orientation -u %s";
        return BytesTool.getInt(ProcessCommandTool.getProcessLocalCommandStr(String.format(commandLine, sib, udId)));
    }

    public static String getSize(String udId) {
        String commandLine = "%s info -d com.apple.mobile.iTunes -u %s";
        String re = ProcessCommandTool.getProcessLocalCommandStr(String.format(commandLine, sib, udId));
        String size = "";
        try {
            JSONObject r = JSON.parseObject(re);
            size = r.getInteger("ScreenWidth") + "x" + r.getInteger("ScreenHeight");
        } catch (Throwable ignored) {
        }
        return size;
    }

    public static int getScreenScale(String udId) {
        String commandLine = "%s info -d com.apple.mobile.iTunes -u %s";
        String re = ProcessCommandTool.getProcessLocalCommandStr(String.format(commandLine, sib, udId));
        int size = 2;
        try {
            JSONObject r = JSON.parseObject(re);
            size = r.getInteger("ScreenScaleFactor");
        } catch (Throwable ignored) {
        }
        return size;
    }

    public static void mount(String udId) {
        String commandLine = "%s mount -u %s";
        String re = ProcessCommandTool.getProcessLocalCommandStr(String.format(commandLine, sib, udId));
        logger.info(re);
    }

    public static List<JSONObject> getWebView(String udId) {
        int port;
        if (webViewMap.get(udId) != null) {
            port = webViewMap.get(udId);
        } else {
            port = startWebInspector(udId);
            if (port != 0) {
                webViewMap.put(udId, port);
            } else {
                return new ArrayList<>();
            }
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        ResponseEntity<JSONArray> responseEntity = restTemplate.exchange("http://localhost:" + port + "/json/list",
                HttpMethod.GET, new HttpEntity(headers), JSONArray.class);
        if (responseEntity.getStatusCode() == HttpStatus.OK) {
            return responseEntity.getBody().toJavaList(JSONObject.class);
        } else {
            return new ArrayList<>();
        }
    }

    public static void stopPerfmon(String udId) {
        String processName = String.format("process-%s-perfmon", udId);
        if (GlobalProcessMap.getMap().get(processName) != null) {
            Process ps = GlobalProcessMap.getMap().get(processName);
            ps.children().forEach(ProcessHandle::destroy);
            ps.destroy();
        }
    }

    public static void startPerfmon(String udId, String bundleId, Session session, LogUtil logUtil, int interval) {
        stopPerfmon(udId);
        Process ps = null;
        String commandLine = "%s perfmon -r %d --sys-cpu --sys-mem --sys-disk --sys-network --fps --gpu -u %s%s ";
        String system = System.getProperty("os.name").toLowerCase();
        String tail = bundleId.isEmpty() ? "" : (" --proc-cpu --proc-mem -b " + bundleId);
        try {
            if (system.contains("win")) {
                ps = Runtime.getRuntime()
                        .exec(new String[]{"cmd", "/c", String.format(commandLine, sib, interval, udId, tail)});
            } else if (system.contains("linux") || system.contains("mac")) {
                ps = Runtime.getRuntime()
                        .exec(new String[]{"sh", "-c", String.format(commandLine, sib, interval, udId, tail)});
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        InputStreamReader inputStreamReader = new InputStreamReader(ps.getInputStream());
        BufferedReader stdInput = new BufferedReader(inputStreamReader);
        InputStreamReader err = new InputStreamReader(ps.getErrorStream());
        BufferedReader stdInputErr = new BufferedReader(err);
        Thread psErr = new Thread(() -> {
            String s;
            while (true) {
                try {
                    if ((s = stdInputErr.readLine()) == null)
                        break;
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    break;
                }
                logger.info(s);
            }
            try {
                stdInputErr.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                err.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            logger.info("perfmon print thread shutdown.");
        });
        psErr.start();
        Thread pro = new Thread(() -> {
            String s;
            while (true) {
                try {
                    if ((s = stdInput.readLine()) == null)
                        break;
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    break;
                }
                try {
                    JSONObject perf = JSON.parseObject(s);
                    if (session != null) {
                        JSONObject perfDetail = new JSONObject();
                        perfDetail.put("msg", "perfDetail");
                        perfDetail.put("detail", perf);
                        sendText(session, perfDetail.toJSONString());
                    }
                    if (logUtil != null) {
                        logUtil.sendPerLog(perf.toJSONString());
                    }
                } catch (Exception e) {
                }
            }
            try {
                stdInput.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                inputStreamReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            logger.info("perfmon print thread shutdown.");
        });
        pro.start();
        String processName = String.format("process-%s-perfmon", udId);
        GlobalProcessMap.getMap().put(processName, ps);
    }

    public static void stopShare(String udId) {
        String processName = String.format("process-%s-sib-share", udId);
        if (GlobalProcessMap.getMap().get(processName) != null) {
            Process ps = GlobalProcessMap.getMap().get(processName);
            ps.children().forEach(ProcessHandle::destroy);
            ps.destroy();
        }
    }

    public static void startShare(String udId, Session session) {
        // if (useGoIosTunnel) {
        //     return;
        // }
        String processName = String.format("process-%s-sib-share", udId);
        String commandLine = "%s remote share -u %s -p %d";
        stopShare(udId);
        JSONObject shareJSON = new JSONObject();
        shareJSON.put("msg", "share");
        try {
            String system = System.getProperty("os.name").toLowerCase();
            Process ps = null;
            int port = PortTool.getPort();
            if (system.contains("win")) {
                ps = Runtime.getRuntime()
                        .exec(new String[]{"cmd", "/c", String.format(commandLine, sib, udId, port)});
            } else if (system.contains("linux") || system.contains("mac")) {
                ps = Runtime.getRuntime()
                        .exec(new String[]{"sh", "-c", String.format(commandLine, sib, udId, port)});
            }
            GlobalProcessMap.getMap().put(processName, ps);
            shareJSON.put("port", port);
        } catch (Exception e) {
            shareJSON.put("port", 0);
            e.printStackTrace();
        } finally {
            BytesTool.sendText(session, shareJSON.toJSONString());
        }
    }

    public static void startShare(String udId, int port) {
        String processName = String.format("process-%s-sib-share", udId);
        String commandLine = "%s remote share -u %s -p %d";
        stopShare(udId);
        try {
            String system = System.getProperty("os.name").toLowerCase();
            Process ps = null;
            if (system.contains("win")) {
                ps = Runtime.getRuntime()
                        .exec(new String[]{"cmd", "/c", String.format(commandLine, sib, udId, port)});
            } else if (system.contains("linux") || system.contains("mac")) {
                ps = Runtime.getRuntime()
                        .exec(new String[]{"sh", "-c", String.format(commandLine, sib, udId, port)});
            }
            GlobalProcessMap.getMap().put(processName, ps);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isUpperThanIos17(String udId) {
        JSONObject deviceInfoObj = IOSInfoMap.getDetailMap().get(udId);
        if (deviceInfoObj != null && deviceInfoObj.containsKey("productVersion")) {
            String productVersion = deviceInfoObj.getString("productVersion");
            return CompareVersionUtil.compareVersion(productVersion, "17.0") >= 0;
        }
        return false;
    }

    /**
     * 通过 WDA 的 /status 接口主动获取并更新设备 IP
     * 增加重试机制以应对端口转发建立初期的延迟 (ROI: 稳定性优化)
     *
     * @param udId    设备UDID
     * @param wdaPort 本地转发端口
     */
    private static void updateIpViaWdaStatus(String udId, int wdaPort) {
        IOSDeviceThreadPool.cachedThreadPool.execute(() -> {
            int maxRetries = 3;
            int retryGap = 2000; // 2秒重试一次

            for (int i = 0; i < maxRetries; i++) {
                try {
                    // 给端口转发和WDA初始化预留时间
                    Thread.sleep(retryGap);

                    String url = "http://localhost:" + wdaPort + "/status";
                    logger.info("[{}] 尝试获取设备 IP (第 {}/{} 次): {}", udId, i + 1, maxRetries, url);

                    ResponseEntity<JSONObject> response = restTemplate.getForEntity(url, JSONObject.class);
                    if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        JSONObject value = response.getBody().getJSONObject("value");
                        if (value != null && value.containsKey("ios")) {
                            String ip = value.getJSONObject("ios").getString("ip");
                            if (StringUtils.hasText(ip) && !"127.0.0.1".equals(ip) && !"::1".equals(ip)) {
                                JSONObject detail = IOSInfoMap.getDetailMap().get(udId);
                                if (detail != null) {
                                    detail.put("ipAddress", ip);
                                    logger.info("[{}] [探测成功] 设备局域网 IP 已更新为: {}", udId, ip);
                                    return; // 成功后立即退出循环
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("[{}] 第 {} 次探测 IP 失败: {}", udId, i + 1, e.getMessage());
                }
            }
        });
    }

    /**
     * 从日志中提取设备IP地址并存储
     * 采用通用的优先级竞争机制：非 169.254 (局域网) 优于 169.254 (USB 隧道)
     *
     * @param udId 设备UDID
     * @param log  日志行内容
     */
    private static void extractIpFromLog(String udId, String log) {
        if (log == null) {
            return;
        }

        // 兼容传统的 ServerURLHere 标记和 JSON 格式中的该标记
        String targetKey = "ServerURLHere->";
        if (!log.contains(targetKey)) {
            return;
        }

        try {
            // 提取 http 链接部分
            String sub = log.substring(log.indexOf(targetKey) + targetKey.length());
            // 如果是 JSON 格式，可能带有反斜杠转义
            sub = sub.replace("\\/", "/");

            java.util.regex.Matcher matcher = SERVER_URL_PATTERN.matcher(sub);
            if (matcher.find()) {
                String ip = matcher.group(2);
                // 排除本地回环地址
                if (StringUtils.hasText(ip) && !"127.0.0.1".equals(ip) && !"localhost".equals(ip) && !"::1".equals(ip)) {
                    JSONObject detail = IOSInfoMap.getDetailMap().get(udId);
                    if (detail != null) {
                        String oldIp = detail.getString("ipAddress");
                        // 择优录取：局域网 IP 覆盖 USB 隧道 IP
                        if (!StringUtils.hasText(oldIp) || (oldIp.startsWith("169.254") && !ip.startsWith("169.254"))) {
                            detail.put("ipAddress", ip);
                            logger.info("[{}] 成功捕获/更新设备 IP: {}", udId, ip);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 静默处理
        }
    }

    /**
     * 获取设备IP地址
     *
     * @param udId 设备UDID
     * @return IP地址
     */
    public static String getIpAddress(String udId) {
        JSONObject detail = IOSInfoMap.getDetailMap().get(udId);
        return detail != null ? detail.getString("ipAddress") : null;
    }

}
