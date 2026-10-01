package com.mobile.standalone.service.pool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.Task;

/**
 * 从任务里读出「它要一台什么样的设备」。
 *
 * <p>抽出来是因为有两个地方要问同一个问题，而且必须得到同一个答案：
 * 派活的调度器问「有没有设备能接这个任务」，自动起模拟器的那个问「要不要为它起一台」。
 * 两边各写一份的话，迟早出现「调度器认为派不出去、扩容器认为不用起」这种谁也不动的僵局。
 */
public final class TaskDeviceRequirement {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TaskDeviceRequirement() {
    }

    /**
     * 读不出来时给一组最保守的默认：不限平台、按生产、不许模拟器。
     * 保守的方向是「宁可派不出去」——派错了不会报错，只会安静地给出一份错的结果。
     */
    public static DevicePicker.Requirement of(Task task) {
        if (task == null) {
            return new DevicePicker.Requirement(null, null, false);
        }
        String platform = task.getPlatform() == null ? null : task.getPlatform().name();
        String environment = null;
        boolean allowEmulator = false;
        try {
            JsonNode cfg = JSON.readTree(task.getConfig() == null ? "{}" : task.getConfig());
            if (cfg.hasNonNull("environment")) {
                environment = cfg.get("environment").asText(null);
            }
            JsonNode pool = cfg.get("devicePool");
            if (pool != null && pool.isObject()) {
                if (pool.hasNonNull("platform")) {
                    platform = pool.get("platform").asText(platform);
                }
                if (pool.hasNonNull("allowEmulator")) {
                    allowEmulator = pool.get("allowEmulator").asBoolean(false);
                }
            }
        } catch (Exception e) {
            // 解析不了就按默认走，不该因为一条脏配置让整轮调度停下来
            allowEmulator = false;
        }
        // 生产环境一律不派模拟器，不管任务怎么声明。
        // 执行层已经拦了一道，这里再兜一次是为了不白占一次设备——
        // 拦在执行层意味着任务先被派出去、占了设备、跑起来才失败，队伍里的人白等一轮
        if (!isTest(environment)) {
            allowEmulator = false;
        }
        return new DevicePicker.Requirement(platform, environment, allowEmulator);
    }

    private static boolean isTest(String env) {
        return env != null && "TEST".equalsIgnoreCase(env.trim());
    }
}
