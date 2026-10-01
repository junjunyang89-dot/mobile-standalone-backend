-- 谁改的:给四张核心表加 updated_by
--
-- 背景:created_by 这四张表本来就有列(只是服务端从不写、已在这一轮修好),
-- 但 updated_by 全仓一处都没有——所以「谁建的」现在能查了,「谁改的」还查不到。
-- 而 50 人共用一个元素库和流程库,真正天天发生的是「改」不是「建」。
--
-- 这个文件单独拆出来,因为它是 DDL:要不要现在加、什么时候停机加,该你定。
-- 不跑这个文件,平台一切正常,只是查不到最后是谁改的。
--
-- 幂等:重复执行不会报错(MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS,
-- 所以用存储过程判一下 information_schema)。

DELIMITER $$

DROP PROCEDURE IF EXISTS add_updated_by_if_absent$$
CREATE PROCEDURE add_updated_by_if_absent(IN tbl VARCHAR(64))
BEGIN
    DECLARE col_exists INT DEFAULT 0;
    SELECT COUNT(*) INTO col_exists
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = tbl
       AND COLUMN_NAME = 'updated_by';
    IF col_exists = 0 THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl,
            '` ADD COLUMN `updated_by` VARCHAR(64) NULL COMMENT ''最后修改人(服务端盖章,不接受客户端传值)''');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_updated_by_if_absent('ui_flows');
CALL add_updated_by_if_absent('ui_elements');
CALL add_updated_by_if_absent('test_cases');
CALL add_updated_by_if_absent('api_test_cases');

DROP PROCEDURE IF EXISTS add_updated_by_if_absent;

-- 跑完之后还要做的代码改动(这个 SQL 自己不会让 updated_by 有值):
--   1. UiFlow / UiElement / TestCase / ApiTestCase 四个 entity 各加 private String updatedBy;
--   2. 对应 mapper.xml 的 <result> 加一行、UPDATE 语句的 SET 里加 updated_by = #{updatedBy}
--   3. 四个 service 的 update() 里加一行:
--        existing.setUpdatedBy(com.mobile.standalone.auth.CurrentUser.nameOrNull());
--      —— 和 created_by 一样,服务端盖章、覆盖请求体里的值
--   4. 前端列表加一列「最后修改人」,否则加了也没人看得到
--
-- 第 3 步是关键:只加列不盖章的话,列永远是 NULL,
-- 而「列存在但全是 NULL」比「没有这列」更容易骗人 —— 看的人会以为这功能做了。
-- created_by 原先就正是这个状态。
