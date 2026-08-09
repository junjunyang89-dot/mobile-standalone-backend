-- 说明：这份 SQL 现在是「备份/参考」用的，正常不需要手动跑。
-- ApiTestCaseService 与 ApiProjectService 启动时会自动建表补列（见各自的 @PostConstruct），
-- 唯一例外是下面给 data-center 系列刷业务成功码那条 UPDATE——它改的是数据不是结构，
-- 自动跑会覆盖你手工改过的配置，所以留给人决定。

-- 接口自动化：变量系统 + 业务成功码
--
-- 解决两件事，都是「跑起来之后能不能不用人管」的前提：
--
-- 1) 业务成功码。data-center 这几个后端统一用 ResponseResult{code,message,result} 包一层，
--    业务失败也是 HTTP 200。只断言 status=200 等于只测「接口没挂」——
--    返回 {code:5009001,message:"服务调用失败"} 照样判通过。
--    已导入的 257 条用例现在全是这个状态，跑出来会是一份 100% 通过的假报告。
--    配在项目上而不是逐条用例上：改一行配置，已有用例一条都不用动。
--
-- 2) 变量与 extract。token 写死在 api_test_envs.default_token 里，一过期几百条集体变红，
--    人得手动粘一个新的进去。有了 extract，一条登录用例把 token 抽进本轮上下文，
--    后面的用例引用 {{token}} 就行，每轮自己新鲜。

-- ---------- 1) 业务成功码 ----------
ALTER TABLE api_projects
    ADD COLUMN success_code_path VARCHAR(128) NULL COMMENT '业务成功码路径，如 $.code' AFTER base_branch,
    ADD COLUMN success_code_value VARCHAR(64) NULL COMMENT '业务成功码的值，如 0' AFTER success_code_path;

UPDATE api_projects SET success_code_path = '$.code', success_code_value = '0'
 WHERE code IN ('data-center', 'global-data', 'app-video', 'appuser', 'event-center')
   AND success_code_path IS NULL;

-- ---------- 2) 环境变量 ----------
CREATE TABLE IF NOT EXISTS env_variables (
    environment VARCHAR(16)  NOT NULL COMMENT '所属环境 TEST/PROD',
    name        VARCHAR(64)  NOT NULL COMMENT '变量名，用例里写 {{name}}',
    value       VARCHAR(2048)     NULL COMMENT '变量值',
    secret      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '敏感值：接口返回给前端时打码，只有执行时才用真值',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '关掉相当于没配这个变量，用来临时试「不传会怎样」',
    description VARCHAR(255)      NULL,
    sort_order  INT          NOT NULL DEFAULT 100,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (environment, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口测试环境变量';

-- baseUrl 已经在 api_test_envs 里，这里补的是除它以外的东西。
-- 不搬 baseUrl 过来：搬了就有两个地方能改 baseUrl，迟早对不上。
INSERT IGNORE INTO env_variables (environment, name, value, secret, description, sort_order) VALUES
    ('TEST', 'token', '', 1, '登录态。建议由登录用例 extract 自动写入，这里留空即可', 10),
    ('PROD', 'token', '', 1, '登录态。建议由登录用例 extract 自动写入，这里留空即可', 10);

-- ---------- 3) 用例：从响应里抽变量 ----------
ALTER TABLE api_test_cases
    ADD COLUMN extract_json TEXT NULL
        COMMENT '执行后抽变量，如 {"token":"$.result.token"}；抽到的值供本轮后续用例引用 {{token}}'
        AFTER assertions;
