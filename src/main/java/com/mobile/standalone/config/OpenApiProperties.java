package com.mobile.standalone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 对外开放接口（{@code /open/v1/**}）：给其它管理平台查用例、下发 UI 自动化任务。
 * <p>
 * 一个调用方一把 key。key 同时用来给回调签名，对方拿同一把 key 验签即可。
 * key 放本机 application.yml，勿提交到公开仓库。
 */
@Data
@ConfigurationProperties(prefix = "open-api")
public class OpenApiProperties {

    private List<Client> clients = new ArrayList<>();

    /**
     * 报告页地址前缀，拼成 {@code {reportBaseUrl}#/tasks/{taskId}/overview} 给对方点开看详情。
     * 不配就不返回报告链接——拼一个打不开的地址比不给更误导。
     */
    private String reportBaseUrl = "";

    /** 一次最多下发多少条用例。设备就那几台，一次塞几百条只会把组里其他人的任务全堵在后面 */
    private int maxCasesPerRun = 100;

    /** 回调失败后最多重试几次（间隔递增） */
    private int callbackMaxAttempts = 5;

    @Data
    public static class Client {
        /** 调用方名称，会出现在任务名和日志里，便于区分是谁下发的 */
        private String name;
        private String key;
        private boolean enabled = true;
    }
}
