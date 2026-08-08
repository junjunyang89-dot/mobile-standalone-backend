-- 流程的前置依赖：JSON 数组，元素是 ui_flows.id。
--
-- 解决的是「20 条用例都要先登录」。以前只有两条路：
--   每条都 CALL_COMPONENT 登录 -> 登录跑 20 次；
--   人工排顺序                 -> 靠人不出错，且换个人就乱。
-- 声明依赖之后由拓扑排序决定顺序，被依赖的流程一次批量里只跑一次。
--
-- 存量流程为 NULL，等于无依赖，行为与加这个字段之前一致。
ALTER TABLE ui_flows
    ADD COLUMN depends_on TEXT NULL COMMENT '前置流程 ID JSON 数组，如 ["uif-a","uif-b"]' AFTER steps;
