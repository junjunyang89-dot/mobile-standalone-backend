package com.mobile.standalone.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行取消登记：把「停止」从改数据库状态变成真正能打断执行线程。
 * <p>
 * 流程执行跑在应用自己的线程里（不是外部进程），原先 {@code stopTask} 只 destroy 外部 Process，
 * 对流程任务毫无作用——数据库标成 STOPPED，手机上却继续跑到底，还会与下一个任务抢设备。
 * <p>
 * 用法：提交执行前 {@link #register}，停止时 {@link #cancel}，执行线程在每步/长循环里查
 * {@link #isCancelled}，结束后 {@link #clear}。
 */
@Service
@Slf4j
public class ExecutionCancelRegistry {

    private final ConcurrentHashMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

    /** 登记一次执行；重复登记会重置为「未取消」 */
    public void register(String executionKey) {
        if (isBlank(executionKey)) {
            return;
        }
        flags.put(executionKey.trim(), new AtomicBoolean(false));
    }

    /**
     * 请求取消。
     *
     * @return 该执行处于登记中（即确实在跑）返回 true；未登记返回 false
     */
    public boolean cancel(String executionKey) {
        if (isBlank(executionKey)) {
            return false;
        }
        AtomicBoolean flag = flags.get(executionKey.trim());
        if (flag == null) {
            return false;
        }
        flag.set(true);
        log.info("已请求取消执行: key={}", executionKey);
        return true;
    }

    /**
     * 执行线程是否仍在运行中。
     * <p>
     * 登记发生在提交时、清理发生在执行线程的 finally，因此这里是「线程是否还活着」的准确信号。
     * 注意任务的数据库状态在收到停止请求时就会立刻变成 STOPPED，比线程真正收尾早——
     * 需要等设备真正空出来的调用方（如线上回归串行跑下一步）必须以本方法为准。
     */
    public boolean isActive(String executionKey) {
        return !isBlank(executionKey) && flags.containsKey(executionKey.trim());
    }

    /** 是否已被请求取消；未登记视为未取消 */
    public boolean isCancelled(String executionKey) {
        if (isBlank(executionKey)) {
            return false;
        }
        AtomicBoolean flag = flags.get(executionKey.trim());
        return flag != null && flag.get();
    }

    /** 执行结束后清理，避免同一 id 复跑时读到上次的取消标记 */
    public void clear(String executionKey) {
        if (isBlank(executionKey)) {
            return;
        }
        flags.remove(executionKey.trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
