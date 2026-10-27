package com.mobile.standalone.service.openapi;

import com.mobile.standalone.entity.Task;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对外的状态词表。内部任务状态会随功能加（BLOCKED、SKIPPED 都是后来加的），
 * 对外一旦发布就不能随便改，所以在这里收口成一套固定的词。
 */
public final class OpenRunStatus {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    /** 没跑成（设备掉线、应用拉不起来）：不是用例失败，不进通过率 */
    public static final String BLOCKED = "BLOCKED";
    /** 流程判定本次不适用 */
    public static final String SKIPPED = "SKIPPED";
    public static final String CANCELLED = "CANCELLED";
    /** 下发时就没建成任务 */
    public static final String ERROR = "ERROR";

    private OpenRunStatus() {
    }

    public static String ofTask(Task task) {
        if (task == null || task.getStatus() == null) {
            return ERROR;
        }
        return switch (task.getStatus()) {
            case PENDING -> task.getDeviceId() == null || task.getDeviceId().isBlank() ? QUEUED : RUNNING;
            case RUNNING -> RUNNING;
            case COMPLETED -> PASSED;
            case FAILED -> FAILED;
            case STOPPED -> CANCELLED;
            case SKIPPED -> SKIPPED;
            case BLOCKED -> BLOCKED;
        };
    }

    public static boolean isTerminal(String itemStatus) {
        return !QUEUED.equals(itemStatus) && !RUNNING.equals(itemStatus);
    }

    /**
     * 整批的状态。
     * <ul>
     *   <li>还有没跑完的：全在排队＝QUEUED，否则 RUNNING</li>
     *   <li>被取消过：CANCELLED（哪怕有几条跑完了，这批结果也不完整）</li>
     *   <li>全部通过或跳过：PASSED</li>
     *   <li>有失败：FAILED；没有失败但有没跑成的：BLOCKED——两者处理方式不同，
     *       前者查用例和 APP，后者查设备</li>
     * </ul>
     */
    public static String ofRun(Collection<String> itemStatuses, boolean cancelled) {
        if (itemStatuses == null || itemStatuses.isEmpty()) {
            return cancelled ? CANCELLED : ERROR;
        }
        boolean anyOpen = false;
        boolean allQueued = true;
        for (String s : itemStatuses) {
            if (!isTerminal(s)) {
                anyOpen = true;
            }
            if (!QUEUED.equals(s)) {
                allQueued = false;
            }
        }
        if (anyOpen && !cancelled) {
            return allQueued ? QUEUED : RUNNING;
        }
        if (cancelled) {
            return CANCELLED;
        }
        if (itemStatuses.stream().anyMatch(s -> FAILED.equals(s) || ERROR.equals(s))) {
            return FAILED;
        }
        if (itemStatuses.stream().anyMatch(s -> BLOCKED.equals(s) || CANCELLED.equals(s))) {
            return BLOCKED;
        }
        return PASSED;
    }

    /** 各状态条数 + 通过率（分母只算真正跑到断言的：通过 + 失败） */
    public static Map<String, Object> summary(Collection<String> itemStatuses) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : new String[]{QUEUED, RUNNING, PASSED, FAILED, BLOCKED, SKIPPED, CANCELLED, ERROR}) {
            counts.put(s, 0);
        }
        for (String s : itemStatuses) {
            counts.merge(s, 1, Integer::sum);
        }
        int passed = counts.get(PASSED);
        int judged = passed + counts.get(FAILED);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", itemStatuses.size());
        out.put("counts", counts);
        out.put("passRate", judged == 0 ? null : Math.round(passed * 1000.0 / judged) / 10.0);
        return out;
    }
}
