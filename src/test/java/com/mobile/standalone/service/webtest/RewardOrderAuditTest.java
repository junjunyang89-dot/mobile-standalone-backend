package com.mobile.standalone.service.webtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 从奖励接口 JSON 自动抽任务奖品。用「国服战令」的真实字段结构
 * （taskList → task.awards[].name）做基准。这一步认不出结构，整个自动校验就白搭。
 */
class RewardOrderAuditTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final RewardOrderAudit audit = new RewardOrderAudit(null);

    private JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    /** 真实结构：{code,message,result:{taskList:[{taskName,awards:[{name}]}]}} */
    @Test
    void 抽取国服战令的任务奖品结构() throws Exception {
        JsonNode root = json("""
                {"code":0,"message":"ok","result":{"taskList":[
                  {"taskName":"福利任务I","awards":[
                     {"name":"大会员","id":1159},{"name":"好运券","id":840}]},
                  {"taskName":"福利任务II","awards":[
                     {"name":"赛季重置卡","id":843},{"name":"段位保护盾","id":844}]}
                ]}}""");

        var tasks = audit.extractTasks(root);

        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).get("taskName")).isEqualTo("福利任务I");
        @SuppressWarnings("unchecked")
        List<String> r0 = (List<String>) tasks.get(0).get("rewards");
        assertThat(r0).containsExactly("大会员", "好运券");
    }

    /** 字段名换一套（rewards/awardName）也要能兜住——换个活动不用改代码 */
    @Test
    void 兼容不同字段名() throws Exception {
        JsonNode root = json("""
                {"result":{"list":[
                  {"name":"任务A","rewards":[{"awardName":"改名卡"},{"awardName":"礼包"}]}
                ]}}""");

        var tasks = audit.extractTasks(root);
        assertThat(tasks).hasSize(1);
        @SuppressWarnings("unchecked")
        List<String> r = (List<String>) tasks.get(0).get("rewards");
        assertThat(r).containsExactly("改名卡", "礼包");
    }

    /** 顶层直接是数组也要能抽 */
    @Test
    void 顶层数组() throws Exception {
        JsonNode root = json("""
                [{"taskName":"T","awards":[{"name":"大会员"}]}]""");
        assertThat(audit.extractTasks(root)).hasSize(1);
    }

    /** 没有奖品数组的对象不该被当成任务（避免误抽外层包装） */
    @Test
    void 不误抽没有奖品的对象() throws Exception {
        JsonNode root = json("""
                {"result":{"userInfo":{"name":"张三","level":5}}}""");
        assertThat(audit.extractTasks(root)).isEmpty();
    }

    /** 空奖品的任务跳过，不产生一个空条目 */
    @Test
    void 跳过空奖品任务() throws Exception {
        JsonNode root = json("""
                {"result":{"taskList":[
                  {"taskName":"有奖","awards":[{"name":"大会员"}]},
                  {"taskName":"无奖","awards":[]}
                ]}}""");
        assertThat(audit.extractTasks(root)).hasSize(1);
    }
}
