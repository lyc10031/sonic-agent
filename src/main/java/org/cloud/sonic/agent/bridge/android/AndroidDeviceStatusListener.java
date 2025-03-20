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
package org.cloud.sonic.agent.bridge.android;

import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.NullOutputReceiver;
import org.cloud.sonic.agent.common.interfaces.IsHMStatus;
import org.cloud.sonic.agent.common.interfaces.PlatformType;
import org.cloud.sonic.agent.common.maps.AndroidDeviceManagerMap;
import org.cloud.sonic.agent.common.maps.DevicesBatteryMap;
import org.cloud.sonic.agent.transport.TransportWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author ZhouYiXun
 * @des adb上下线监听，发送对应给server
 * @date 2021/08/16 19:26
 */
@Component
public class AndroidDeviceStatusListener implements AndroidDebugBridge.IDeviceChangeListener {
    private final Logger logger = LoggerFactory.getLogger(AndroidDeviceStatusListener.class);

    // 新增设备状态缓存
    private static final Map<String, Long> lastEventTimeMap = new ConcurrentHashMap<>();
    private static final long EVENT_COOLDOWN_MS = 3000;

    // 优化点1：使用异步线程池处理设备状态上报
    private static final ExecutorService statusExecutor = Executors.newSingleThreadExecutor();

    // 优化点2：静态信息缓存
    private static final Map<String, JSONObject> deviceInfoCache = new ConcurrentHashMap<>();

    private void send(IDevice device) {
        JSONObject deviceDetail = buildDeviceInfo(device);
        // 优化点3：异步上报设备状态
        statusExecutor.execute(() -> TransportWorker.send(deviceDetail));
    }

    // 新增方法：构建设备信息（带缓存）
    private JSONObject buildDeviceInfo(IDevice device) {
        String udid = device.getSerialNumber();
        JSONObject cached = deviceInfoCache.get(udid);
        if (cached != null) {
            return cached;
        }

        JSONObject deviceDetail = new JSONObject();
        try {
            // 优化点4：双检锁保证线程安全
            synchronized (this) {
                if (deviceInfoCache.containsKey(udid)) {
                    return deviceInfoCache.get(udid);
                }

                // 基础信息
                deviceDetail.put("msg", "deviceDetail");
                deviceDetail.put("udId", udid);

                // 优化点5：批量获取设备属性
                Map<String,Object> props = getDeviceProperties(device);
                deviceDetail.put("name", props.get("ro.product.name"));
                deviceDetail.put("model", props.get(IDevice.PROP_DEVICE_MODEL));
                // ... 其他属性类似处理 ...

                // 缓存静态信息
                deviceInfoCache.put(udid, deviceDetail);
            }
        } catch (Exception e) {
            logger.error("Build device info failed: {}", e.getMessage());
        }
        return deviceDetail;
    }

    // 新增方法：批量获取设备属性
    private Map<String, Object> getDeviceProperties(IDevice device) {
        Map<String, Object> props = new HashMap<>();
        try {
            props.put("msg", "deviceDetail");
            props.put("udId", device.getSerialNumber());
            props.put("ro.product.name", device.getProperty("ro.product.name"));
            props.put(IDevice.PROP_DEVICE_MODEL, device.getProperty(IDevice.PROP_DEVICE_MODEL));
            // ... 其他需要获取的属性 ...
            props.put("model", device.getProperty(IDevice.PROP_DEVICE_MODEL));
            props.put("status", device.getState() == null ? null : device.getState().toString());
            props.put("platform", PlatformType.ANDROID);
            if (device.getProperty("ro.config.ringtone") != null && device.getProperty("ro.config.ringtone").contains("Harmony")) {
                props.put("version", device.getProperty("hw_sc.build.platform.version"));
                props.put("isHm", IsHMStatus.IS_HM);
            } else {
                props.put("version", device.getProperty(IDevice.PROP_BUILD_VERSION));
                props.put("isHm", IsHMStatus.IS_ANDROID);
            }

            props.put("size", AndroidDeviceBridgeTool.getScreenSize(device));
            props.put("cpu", device.getProperty(IDevice.PROP_DEVICE_CPU_ABI));
            props.put("manufacturer", device.getProperty(IDevice.PROP_DEVICE_MANUFACTURER));
        } catch (Exception e) {
            logger.warn("Get device properties failed: {}", e.getMessage());
        }
        return props;
    }
//    /**
//     * @param device
//     * @return void
//     * @author ZhouYiXun
//     * @des 发送设备状态
//     * @date 2021/8/16 19:58
//     */
//    private void send(IDevice device) {
//        JSONObject deviceDetail = new JSONObject();
//        deviceDetail.put("msg", "deviceDetail");
//        deviceDetail.put("udId", device.getSerialNumber());
//        deviceDetail.put("name", device.getProperty("ro.product.name"));
//        deviceDetail.put("model", device.getProperty(IDevice.PROP_DEVICE_MODEL));
//        deviceDetail.put("status", device.getState() == null ? null : device.getState().toString());
//        deviceDetail.put("platform", PlatformType.ANDROID);
//        if (device.getProperty("ro.config.ringtone") != null && device.getProperty("ro.config.ringtone").contains("Harmony")) {
//            deviceDetail.put("version", device.getProperty("hw_sc.build.platform.version"));
//            deviceDetail.put("isHm", IsHMStatus.IS_HM);
//        } else {
//            deviceDetail.put("version", device.getProperty(IDevice.PROP_BUILD_VERSION));
//            deviceDetail.put("isHm", IsHMStatus.IS_ANDROID);
//        }
//
//        deviceDetail.put("size", AndroidDeviceBridgeTool.getScreenSize(device));
//        deviceDetail.put("cpu", device.getProperty(IDevice.PROP_DEVICE_CPU_ABI));
//        deviceDetail.put("manufacturer", device.getProperty(IDevice.PROP_DEVICE_MANUFACTURER));
//        TransportWorker.send(deviceDetail);
//    }

    @Override
    public void deviceConnected(IDevice device) {
        // 优化点6：连接事件去重
        long now = System.currentTimeMillis();
        Long lastEvent = lastEventTimeMap.get(device.getSerialNumber());
        if (lastEvent != null && now - lastEvent < EVENT_COOLDOWN_MS) {
            return;
        }
        logger.info("Android device: " + device.getSerialNumber() + " ONLINE！");
        AndroidDeviceManagerMap.getStatusMap().remove(device.getSerialNumber());
        DevicesBatteryMap.getTempMap().remove(device.getSerialNumber());
        send(device);

        lastEventTimeMap.put(device.getSerialNumber(), now);


        // 优化点7：启动心跳检测
        startHeartbeatCheck(device);
    }
    private void startHeartbeatCheck(IDevice device) {
        final String serial = device.getSerialNumber();
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final AtomicReference<IDevice> deviceRef = new AtomicReference<>(device);

        final ScheduledFuture<?> heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            IDevice currentDevice = deviceRef.get();
            try {
                // 增加设备有效性校验
                if (currentDevice == null || !currentDevice.isOnline()) {
                    logger.warn("Device {} invalid or offline!", serial);
                    scheduler.shutdown();
                    return;
                }

                // 状态检查增加双重校验
                boolean isUnauthorized = currentDevice.getState() == IDevice.DeviceState.UNAUTHORIZED;
                if (isUnauthorized) {
                    logger.warn("Device {} authorization check: UNAUTHORIZED", serial);
                    send(currentDevice); // 保持状态同步
                } else {
                    // 增加连接稳定性检查
                    if (!checkDeviceConnectivity(currentDevice)) {
                        logger.warn("Device {} connection unstable!", serial);
                        deviceDisconnected(currentDevice);
                        scheduler.shutdown();
                    }
                }
            } catch (Exception e) {
                logger.error("Device {} heartbeat error: {}", serial, e.getMessage());
                scheduler.shutdown();
            }
        }, 10, 10, TimeUnit.SECONDS);

        // 增加优雅关闭逻辑
        scheduler.schedule(() -> {
            if (!heartbeatFuture.isDone()) {
                heartbeatFuture.cancel(true);
                scheduler.shutdown();
            }
        }, 1, TimeUnit.HOURS);
    }

    // 新增设备连接稳定性校验
    private boolean checkDeviceConnectivity(IDevice device) {
        try {
            // 简单指令测试连接
            device.executeShellCommand("echo check", new NullOutputReceiver());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void deviceDisconnected(IDevice device) {
        logger.info("Android device: " + device.getSerialNumber() + " OFFLINE！");
        AndroidDeviceManagerMap.getStatusMap().remove(device.getSerialNumber());
        DevicesBatteryMap.getTempMap().remove(device.getSerialNumber());
        send(device);
    }

    @Override
    public void deviceChanged(IDevice device, int changeMask) {
        IDevice.DeviceState state = device.getState();
        if (state == IDevice.DeviceState.OFFLINE) {
            logger.warn("Android device: " + device.getSerialNumber() + " OFFLINE！");
            return;
        }
        send(device);
    }
}
