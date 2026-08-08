-- 接口项目关联本地代码仓库，用于「分支 vs 基线」的接口变更分析。
-- 只读扫描：分析过程只跑 git diff / git show，不会改动工作区。
ALTER TABLE api_projects
    ADD COLUMN repo_path VARCHAR(512) NULL COMMENT '本地 git 仓库绝对路径，如 /Users/x/projects/data-center' AFTER description,
    ADD COLUMN base_branch VARCHAR(128) NULL COMMENT '对比基线分支，默认 master' AFTER repo_path;
