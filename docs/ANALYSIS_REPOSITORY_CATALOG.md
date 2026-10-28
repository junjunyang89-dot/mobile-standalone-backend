# Analysis repository catalog

Local APP root: `ANALYSIS_APP_REPOS_ROOT`, default `${user.home}/projects/qa_coding/app_coding`.
PVP paths retain the existing `${user.home}/mobile-standalone-repos` configuration.

Project IDs, local roots, JIRA aliases and business groups are maintained under `analysis-bridge` in `src/main/resources/application.yml`. Registering a project does NOT add it to every task. Only explicit projects and branches in the current JIRA participate. Registered projects default to master. Before each analysis, fetch origin/master into an isolated analysis ref and pin its SHA. A failed master fetch blocks the task (LATEST_MASTER_UNAVAILABLE); never label a stale local snapshot as the latest master.

## Configured APP

- app-activity
- app-admin
- app-community
- app-decoration
- app-engine
- app-push
- app-swift
- app-swift-static
- app-swift-v3
- app-video
- app-xiaomai
- app_esports_android
- app_esports_harmonyos
- esportapp-ios
- robot
- commerce-channel

## Configured PVP

- pvpmatchapi.wmpvp.com
- pvpmatchcore.wmpvp.com
- pvpapj.wmpvp.com
- pvpmatchadmin.wmpvp.com
- electron-pvp

## Pending

WEB/ESPORTS repository lists and business ownership have not been confirmed. Other locally present services are not automatically registered or classified as shared. GitLab namespaces such as Esports or app_web do not prove a business group. Supply project ID, group, clone URL/local root and common JIRA names to add them. Do not register deployment/configuration/QA infrastructure as business repositories by default.

After updating mappings restart the backend and reread/reclassify the current JIRA. Existing completed reports and test records are not rewritten.

## APP-9227 关联转盘服务

`pvp-galaxy`：`http://gitlab.shdev.net/Esports/pvp-galaxy.git`，本地 `${user.home}/mobile-standalone-repos/pvp-galaxy`，master 基线。用户确认本次提测分支 `PVP-13893`，作为 APP-9227 的关联服务分析，不将两个需求的产品规则自动混合。仓库允许 SHARED 跨组引用，但只有本单明确选择才参与分析。
