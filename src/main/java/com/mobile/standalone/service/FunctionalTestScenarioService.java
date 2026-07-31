package com.mobile.standalone.service;

import com.mobile.standalone.entity.Task;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 功能测试场景注册表
 * 功能基础质量测试下按场景键（launch_app/login/search）区分，并按平台走不同执行路径。
 */
@Service
public class FunctionalTestScenarioService {

    /** 场景键：启动应用程序 */
    public static final String SCENARIO_LAUNCH_APP = "launch_app";
    /** 场景键：启动 PogPog Android（国际版包名与 LauncherActivity，见 {@link LoginTestService} 常量） */
    public static final String SCENARIO_LAUNCH_POGPOG_ANDROID = "launch_pogpog_android";
    /** 场景键：登录 */
    public static final String SCENARIO_LOGIN = "login";
    /** 场景键：搜索 */
    public static final String SCENARIO_SEARCH = "search";
    /** 场景键：搜索Tab切换 */
    public static final String SCENARIO_SEARCH_TABS = "search_tabs";
    /** 场景键：搜索用户添加关注 */
    public static final String SCENARIO_SEARCH_USER_FOLLOW = "search_user_follow";
    /** 场景键：搜索-最新/最热视频播放 */
    public static final String SCENARIO_SEARCH_VIDEO_LATEST_HOTTEST = "search_video_latest_hottest";
    /** 场景键：CS资讯页浏览 */
    public static final String SCENARIO_CS_NEWS_BROWSING = "cs_news_browsing";
    /** 场景键：CS-动态页浏览 */
    public static final String SCENARIO_CS_DYNAMIC_BROWSING = "cs_dynamic_browsing";
    /** 场景键：CS-动态-关注更多战队选手 */
    public static final String SCENARIO_CS_DYNAMIC_FOLLOW_MORE = "cs_dynamic_follow_more";
    /** 场景键：CS-热评-浏览 */
    public static final String SCENARIO_CS_HOT_COMMENT_BROWSE = "cs_hot_comment_browse";
    /** 场景键：CS-热评点赞 */
    public static final String SCENARIO_CS_HOT_COMMENT_LIKE = "cs_hot_comment_like";
    /** 场景键：CS-热评页面跳转至用户个人页面 */
    public static final String SCENARIO_CS_HOT_COMMENT_TO_USER_PROFILE = "cs_hot_comment_to_user_profile";
    /** 场景键：CS-热评内容跳转 */
    public static final String SCENARIO_CS_HOT_COMMENT_CONTENT_JUMP = "cs_hot_comment_content_jump";
    /** 场景键：CS-评论热评内容 */
    public static final String SCENARIO_CS_COMMENT_HOT_COMMENT_CONTENT = "cs_comment_hot_comment_content";
    /** 场景键：CS-视频浏览 */
    public static final String SCENARIO_CS_VIDEO_BROWSE = "cs_video_browse";
    /** 场景键：CS-视频播放|点赞|评论|分享 */
    public static final String SCENARIO_CS_VIDEO_PLAY_LIKE_COMMENT_SHARE = "cs_video_play_like_comment_share";
    /** 场景键：CS-活动浏览 */
    public static final String SCENARIO_CS_ACTIVITY_BROWSE = "cs_activity_browse";
    /** 场景键：CS-赛事-赛程浏览 */
    public static final String SCENARIO_CS_EVENT_SCHEDULE_BROWSE = "cs_event_schedule_browse";
    /** 场景键：CS-已结束比赛-赛事详情-数据 */
    public static final String SCENARIO_CS_ENDED_MATCH_DETAIL_DATA = "cs_ended_match_detail_data";
    /** 场景键：CS-已结束比赛-赛事详情-分析 */
    public static final String SCENARIO_CS_ENDED_MATCH_DETAIL_ANALYSIS = "cs_ended_match_detail_analysis";
    /** 场景键：设置 */
    public static final String SCENARIO_SETTINGS = "settings";
    /** 场景键：设置-我的账号 */
    public static final String SCENARIO_SETTINGS_ACCOUNT = "settings_account";
    /** 场景键：设置-我的兴趣 */
    public static final String SCENARIO_SETTINGS_INTERESTS = "settings_interests";
    /** 场景键：设置-高校认证 */
    public static final String SCENARIO_SETTINGS_SCHOOL_CERT = "settings_school_cert";

    /**
     * 可执行场景注册表（完美世界电竞 / 鸿蒙等）。
     * Pog-Android、Pog-iOS 对应独立应用 PogPog：不与 Android/iOS 共用场景条目；设备/应用列表仍由接口层与 Android/iOS 合并。
     * 后续若有 Pog 专用自动化，在此追加 {@code platform} 为 {@code POG_ANDROID} / {@code POG_IOS} 的 {@link ScenarioDefinition} 即可。
     */
    private static final List<ScenarioDefinition> SCENARIOS = List.of(
            // 安装卸载升级（置为第一个功能）
            new ScenarioDefinition("install_upgrade_android", "安装卸载升级测试(Android)", "ANDROID", Task.TaskType.INSTALL_UNINSTALL_UPGRADE_TEST, "Android 应用安装、卸载与升级流程验证", null),
            new ScenarioDefinition("install_upgrade_ios", "安装卸载升级测试(iOS)", "IOS", Task.TaskType.INSTALL_UNINSTALL_UPGRADE_TEST, "iOS 应用安装、卸载与升级流程验证", null),
            // 功能基础质量测试 - 启动应用程序（按平台）
            new ScenarioDefinition("launch_app_android", "启动应用程序(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "Android 应用启动检测", SCENARIO_LAUNCH_APP),
            new ScenarioDefinition("launch_app_ios", "启动应用程序(iOS)", "IOS", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "iOS 应用启动检测", SCENARIO_LAUNCH_APP),
            new ScenarioDefinition("launch_app_harmonyos", "启动应用程序(鸿蒙)", "HARMONYOS", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "鸿蒙应用启动检测", SCENARIO_LAUNCH_APP),
            new ScenarioDefinition("launch_app_pog_android", "启动PogPog应用程序(Android)", "POG_ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST,
                    "PogPog Android：appium:appPackage=com.arca.egames.intl，appium:appActivity=com.arca.egames.intl/.LauncherActivity", SCENARIO_LAUNCH_POGPOG_ANDROID),
            // 功能基础质量测试 - 登录（按平台）
            new ScenarioDefinition("login_android", "登录测试(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "完美世界电竞 Android 登录流程", SCENARIO_LOGIN),
            new ScenarioDefinition("login_ios", "登录测试(iOS)", "IOS", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "完美世界电竞 iOS 登录流程", SCENARIO_LOGIN),
            // 功能基础质量测试 - 搜索（按平台）
            new ScenarioDefinition("search_android", "搜索测试(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "Android 应用内搜索流程", SCENARIO_SEARCH),
            new ScenarioDefinition("search_ios", "搜索测试(iOS)", "IOS", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "iOS 应用内搜索流程", SCENARIO_SEARCH),
            new ScenarioDefinition("search_tabs_android", "搜索顶部Tab切换(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录→点击搜索→输入Kak1→校验综合Tab→依次切换新闻/社区/视频/梗百科/选手/战队/用户", SCENARIO_SEARCH_TABS),
            new ScenarioDefinition("search_user_follow_android", "搜索用户添加关注(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录→点击搜索→输入Kak1→校验结果→点击更多→用户Tab→添加关注", SCENARIO_SEARCH_USER_FOLLOW),
            new ScenarioDefinition("search_video_latest_hottest_android", "搜索-最新/最热视频播放(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录→点击搜索→输入Kak1→点击视频Tab→最新/最热视频播放→加载超时返回→向上滑动至srl_classics_title", SCENARIO_SEARCH_VIDEO_LATEST_HOTTEST),
            new ScenarioDefinition("cs_news_browsing_android", "CS资讯页浏览(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录或未登录→点击CS→点击资讯→进入资讯页后向上滑动10次、向下滑动6次", SCENARIO_CS_NEWS_BROWSING),
            new ScenarioDefinition("cs_dynamic_browsing_android", "CS-动态页浏览(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→未登录则登录→判断CS出现并点击→点击动态→向上5次、向下5次循环直到出现「关注更多战队选手」", SCENARIO_CS_DYNAMIC_BROWSING),
            new ScenarioDefinition("cs_dynamic_follow_more_android", "CS-动态-关注更多战队选手(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录→CS→动态→选择关注→选手/战队/赛事/其他/选手(滑动2次)→返回", SCENARIO_CS_DYNAMIC_FOLLOW_MORE),
            new ScenarioDefinition("cs_hot_comment_browse_android", "CS-热评-浏览(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录→CS→热评(若出现则点击)→热评页向上5次、向下5次滑动", SCENARIO_CS_HOT_COMMENT_BROWSE),
            new ScenarioDefinition("cs_hot_comment_like_android", "CS-热评点赞(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录→CS→热评→首条点赞→向上滑动至排行N→点击排行右侧点赞", SCENARIO_CS_HOT_COMMENT_LIKE),
            new ScenarioDefinition("cs_hot_comment_to_user_profile_android", "CS-热评页面跳转至用户个人页面(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录→CS→热评→向上滑动至排行N→点击排行旁头像→进入用户个人页面", SCENARIO_CS_HOT_COMMENT_TO_USER_PROFILE),
            new ScenarioDefinition("cs_hot_comment_content_jump_android", "CS-热评内容跳转(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录→CS→热评→若出现评论内容则点击", SCENARIO_CS_HOT_COMMENT_CONTENT_JUMP),
            new ScenarioDefinition("cs_comment_hot_comment_content_android", "CS-评论热评内容(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录→CS→热评→点击评论内容→进入评论页→向上滑动(出现已显示全部则停止,未出现则滑动几次后也停止)→输入不错→表情包→选择不上班→发布→点击关闭→点击返回", SCENARIO_CS_COMMENT_HOT_COMMENT_CONTENT),
            new ScenarioDefinition("cs_video_browse_android", "CS-视频浏览(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→未登录则登录→判断CS出现并点击→判断视频出现并点击→进入CS-视频页→向上滑动浏览", SCENARIO_CS_VIDEO_BROWSE),
            new ScenarioDefinition("cs_video_play_like_comment_share_android", "CS-视频播放|点赞|评论|分享(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→未登录则登录→CS→视频→选择第一个视频点击播放→点赞→评论(输入不错+表情加油加油→发布)→关闭→返回→分享→取消", SCENARIO_CS_VIDEO_PLAY_LIKE_COMMENT_SHARE),
            new ScenarioDefinition("cs_activity_browse_android", "CS-活动浏览(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→未登录则登录→CS→资讯处向左滑动至出现活动→点击活动→向上5次、向下5次滑动→随机点击一个活动进入", SCENARIO_CS_ACTIVITY_BROWSE),
            new ScenarioDefinition("cs_event_schedule_browse_android", "CS-赛事-赛程浏览(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→未登录则登录→CS→点击赛事→赛程页向上4次、向下4次→今天处向左滑4次→点击周×→周×处向右滑4次→点击周×", SCENARIO_CS_EVENT_SCHEDULE_BROWSE),
            new ScenarioDefinition("cs_ended_match_detail_data_android", "CS-已结束比赛-赛事详情-数据(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录→CS→赛事→随机点击已结束→数据页→若存在地图BP则执行地图分析→总览→基础数据/对位数据→若存在地图BP则查找地图依次点击，地图页若对位数据不存在则跳过后续", SCENARIO_CS_ENDED_MATCH_DETAIL_DATA),
            new ScenarioDefinition("cs_ended_match_detail_analysis_android", "CS-已结束比赛-赛事详情-分析(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "启动→登录→CS→赛事→随机点击已结束→分析→对阵图→概览→参赛队伍(提取战队数据至测试结果分布)", SCENARIO_CS_ENDED_MATCH_DETAIL_ANALYSIS),
            // 功能基础质量测试 - 设置（按平台，需完美世界电竞应用且已登录）
            new ScenarioDefinition("settings_android", "设置测试(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "完美世界电竞 Android 设置页与账号相关功能", SCENARIO_SETTINGS),
            new ScenarioDefinition("settings_ios", "设置测试(iOS)", "IOS", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "完美世界电竞 iOS 设置页与账号相关功能", SCENARIO_SETTINGS),
            // 功能基础质量测试 - 设置-我的账号（按平台）
            new ScenarioDefinition("settings_account_android", "设置-我的账号(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "完美世界电竞 Android 设置页进入我的账号并截图", SCENARIO_SETTINGS_ACCOUNT),
            new ScenarioDefinition("settings_account_ios", "设置-我的账号(iOS)", "IOS", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "完美世界电竞 iOS 设置页进入我的账号并截图", SCENARIO_SETTINGS_ACCOUNT),
            // 功能基础质量测试 - 设置-我的兴趣（按平台）
            new ScenarioDefinition("settings_interests_android", "设置-我的兴趣(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "完美世界电竞 Android 设置页进入我的兴趣并截图", SCENARIO_SETTINGS_INTERESTS),
            new ScenarioDefinition("settings_interests_ios", "设置-我的兴趣(iOS)", "IOS", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "完美世界电竞 iOS 设置页进入我的兴趣并截图", SCENARIO_SETTINGS_INTERESTS),
            // 功能基础质量测试 - 设置-高校认证（按平台）
            new ScenarioDefinition("settings_school_cert_android", "设置-高校认证(Android)", "ANDROID", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "完美世界电竞 Android 设置页进入高校认证并截图", SCENARIO_SETTINGS_SCHOOL_CERT),
            new ScenarioDefinition("settings_school_cert_ios", "设置-高校认证(iOS)", "IOS", Task.TaskType.FUNCTIONAL_QUALITY_TEST, "完美世界电竞 iOS 设置页进入高校认证并截图", SCENARIO_SETTINGS_SCHOOL_CERT),
            // 兼容性 / 界面适配（无 scenarioKey，沿用原逻辑）
            new ScenarioDefinition("compatibility_android", "兼容性测试(Android)", "ANDROID", Task.TaskType.COMPATIBILITY_TEST, "Android 设备与应用兼容性检查", null),
            new ScenarioDefinition("compatibility_ios", "兼容性测试(iOS)", "IOS", Task.TaskType.COMPATIBILITY_TEST, "iOS 设备与应用兼容性检查", null),
            new ScenarioDefinition("ui_adapt_android", "界面与适配测试(Android)", "ANDROID", Task.TaskType.UI_ADAPTATION_TEST, "Android 界面展示与多分辨率适配检查", null),
            new ScenarioDefinition("ui_adapt_ios", "界面与适配测试(iOS)", "IOS", Task.TaskType.UI_ADAPTATION_TEST, "iOS 界面展示与多分辨率适配检查", null)
    );

    /** 模块展示顺序：安装卸载置为第一个功能模块 */
    private static final List<String> MODULE_ORDER = List.of(
            "install_upgrade",
            "base",
            "search",
            "cs_news",
            "cs_dynamic",
            "cs_hot_comment",
            "cs_video",
            "cs_activity",
            "cs_event_schedule",
            "settings",
            "other"
    );

    /** 场景→父模块 映射 */
    private static final Map<String, String> SCENARIO_MODULE_MAP = Map.ofEntries(
            Map.entry("launch_app_android", "base"),
            Map.entry("launch_app_ios", "base"),
            Map.entry("launch_app_harmonyos", "base"),
            Map.entry("launch_app_pog_android", "base"),
            Map.entry("login_android", "base"),
            Map.entry("login_ios", "base"),
            Map.entry("search_android", "search"),
            Map.entry("search_ios", "search"),
            Map.entry("search_tabs_android", "search"),
            Map.entry("search_user_follow_android", "search"),
            Map.entry("search_video_latest_hottest_android", "search"),
            Map.entry("cs_news_browsing_android", "cs_news"),
            Map.entry("cs_dynamic_browsing_android", "cs_dynamic"),
            Map.entry("cs_dynamic_follow_more_android", "cs_dynamic"),
            Map.entry("cs_hot_comment_browse_android", "cs_hot_comment"),
            Map.entry("cs_hot_comment_like_android", "cs_hot_comment"),
            Map.entry("cs_hot_comment_to_user_profile_android", "cs_hot_comment"),
            Map.entry("cs_hot_comment_content_jump_android", "cs_hot_comment"),
            Map.entry("cs_comment_hot_comment_content_android", "cs_hot_comment"),
            Map.entry("cs_video_browse_android", "cs_video"),
            Map.entry("cs_video_play_like_comment_share_android", "cs_video"),
            Map.entry("cs_activity_browse_android", "cs_activity"),
            Map.entry("cs_event_schedule_browse_android", "cs_event_schedule"),
            Map.entry("cs_ended_match_detail_data_android", "cs_event_schedule"),
            Map.entry("cs_ended_match_detail_analysis_android", "cs_event_schedule"),
            Map.entry("settings_android", "settings"),
            Map.entry("settings_ios", "settings"),
            Map.entry("settings_account_android", "settings"),
            Map.entry("settings_account_ios", "settings"),
            Map.entry("settings_interests_android", "settings"),
            Map.entry("settings_interests_ios", "settings"),
            Map.entry("settings_school_cert_android", "settings"),
            Map.entry("settings_school_cert_ios", "settings"),
            Map.entry("install_upgrade_android", "install_upgrade"),
            Map.entry("install_upgrade_ios", "install_upgrade"),
            Map.entry("compatibility_android", "other"),
            Map.entry("compatibility_ios", "other"),
            Map.entry("ui_adapt_android", "other"),
            Map.entry("ui_adapt_ios", "other")
    );

    private static final Map<String, String> MODULE_LABELS = Map.ofEntries(
            Map.entry("install_upgrade", "安装卸载升级"),
            Map.entry("base", "基础"),
            Map.entry("search", "搜索"),
            Map.entry("cs_news", "CS-发现-资讯"),
            Map.entry("cs_dynamic", "CS-发现-动态"),
            Map.entry("cs_hot_comment", "CS-发现-热评"),
            Map.entry("cs_video", "CS-发现-视频"),
            Map.entry("cs_activity", "CS-发现-活动"),
            Map.entry("cs_event_schedule", "CS-赛事-赛程"),
            Map.entry("settings", "设置"),
            Map.entry("other", "其他")
    );

    /**
     * 获取所有可执行的功能测试场景（自动化测试列表）
     */
    public List<ScenarioDefinition> listScenarios() {
        return SCENARIOS;
    }

    /**
     * 按平台→模块两层分组返回场景，供前端两层折叠展示。
     * 返回格式：[ { platform, platformLabel, modules: [ { moduleKey, moduleName, scenarios: [...] } ] }, ... ]
     */
    public List<PlatformModuleGroup> listScenariosByPlatformModule() {
        String[] platforms = {"ANDROID", "IOS", "HARMONYOS", "POG_ANDROID", "POG_IOS"};
        String[] labels = {"Android", "iOS", "鸿蒙", "Pog-Android", "Pog-iOS"};
        List<PlatformModuleGroup> result = new ArrayList<>();
        for (int i = 0; i < platforms.length; i++) {
            String p = platforms[i];
            Map<String, List<ScenarioDefinition>> moduleTree = new LinkedHashMap<>();
            for (ScenarioDefinition s : SCENARIOS) {
                if (!p.equals(s.getPlatform())) continue;
                String moduleKey = SCENARIO_MODULE_MAP.getOrDefault(s.getId(), "other");
                moduleTree.computeIfAbsent(moduleKey, k -> new ArrayList<>()).add(s);
            }
            if (moduleTree.isEmpty()) continue;
            List<ModuleGroup> modules = orderModules(moduleTree);
            PlatformModuleGroup pmg = new PlatformModuleGroup();
            pmg.setPlatform(p);
            pmg.setPlatformLabel(labels[i]);
            pmg.setModules(modules);
            result.add(pmg);
        }
        return result;
    }

    /**
     * 按父模块分组返回场景。父模块点击展开后显示子模块（即场景），子模块是做自动化的。
     * 返回格式：[ { moduleKey, moduleName, scenarios: [...] }, ... ]
     */
    public List<ModuleGroup> listScenariosByModule() {
        Map<String, List<ScenarioDefinition>> tree = new LinkedHashMap<>();
        for (ScenarioDefinition s : SCENARIOS) {
            String moduleKey = SCENARIO_MODULE_MAP.getOrDefault(s.getId(), "other");
            tree.computeIfAbsent(moduleKey, k -> new ArrayList<>()).add(s);
        }
        return orderModules(tree);
    }

    /** 按 MODULE_ORDER 排序模块，未列入顺序表的模块追加在末尾 */
    private static List<ModuleGroup> orderModules(Map<String, List<ScenarioDefinition>> moduleTree) {
        List<ModuleGroup> modules = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String key : MODULE_ORDER) {
            List<ScenarioDefinition> scenarios = moduleTree.get(key);
            if (scenarios == null || scenarios.isEmpty()) continue;
            ModuleGroup mg = new ModuleGroup();
            mg.setModuleKey(key);
            mg.setModuleName(MODULE_LABELS.getOrDefault(key, key));
            mg.setScenarios(scenarios);
            modules.add(mg);
            seen.add(key);
        }
        for (Map.Entry<String, List<ScenarioDefinition>> me : moduleTree.entrySet()) {
            if (seen.contains(me.getKey())) continue;
            ModuleGroup mg = new ModuleGroup();
            mg.setModuleKey(me.getKey());
            mg.setModuleName(MODULE_LABELS.getOrDefault(me.getKey(), me.getKey()));
            mg.setScenarios(me.getValue());
            modules.add(mg);
        }
        return modules;
    }

    /**
     * 根据场景 ID 获取定义，用于执行或创建计划
     */
    public ScenarioDefinition getScenario(String scenarioId) {
        return SCENARIOS.stream()
                .filter(s -> s.getId().equals(scenarioId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 仅返回功能基础质量测试场景（供创建任务时「测试场景」下拉用）。
     *
     * @param platformFilter 非空时只返回该平台下的场景（如 ANDROID 与 POG_ANDROID 互不混入）；
     *                       不传则返回全部功能基础质量场景（兼容旧前端）。
     */
    public List<ScenarioDefinition> listFunctionalQualityScenarios(String platformFilter) {
        return SCENARIOS.stream()
                .filter(s -> s.getTaskType() == Task.TaskType.FUNCTIONAL_QUALITY_TEST)
                .filter(s -> platformFilter == null || platformFilter.isBlank()
                        || platformFilter.trim().equalsIgnoreCase(s.getPlatform()))
                .toList();
    }

    /**
     * 任务平台与场景注册表中的 {@code platform} 对齐。
     * Pog（PogPog 应用）不与 Android/iOS 场景目录混用，仅返回任务自身平台名。
     */
    public static String catalogPlatformForScenarios(Task.Platform platform) {
        if (platform == null) {
            return null;
        }
        return platform.name();
    }

    public String getScenarioDisplayName(String scenarioKey, Task.Platform platform) {
        if (scenarioKey == null || platform == null) return null;
        String key = scenarioKey.trim();
        String catalogPl = catalogPlatformForScenarios(platform);
        List<ScenarioDefinition> candidates = SCENARIOS.stream()
                .filter(s -> s.getScenarioKey() != null && s.getScenarioKey().equalsIgnoreCase(key))
                .toList();
        Optional<String> exact = candidates.stream()
                .filter(s -> platform.name().equals(s.getPlatform()))
                .findFirst()
                .map(ScenarioDefinition::getName);
        if (exact.isPresent()) {
            return exact.get();
        }
        return candidates.stream()
                .filter(s -> catalogPl.equals(s.getPlatform()))
                .findFirst()
                .map(ScenarioDefinition::getName)
                .orElseGet(() -> scenarioKey + "(" + platform + ")");
    }

    /**
     * 统一为任务解析「测试场景」展示名，供列表/详情多处复用，避免有的有、有的没有。
     * - 有 scenarioKey + platform：按场景键+平台返回展示名。
     * - 无 scenarioKey 但是「功能基础质量」且 platform 存在：返回「未选择场景(平台)」，老数据也能显示一列文案。
     * - 无 scenarioKey 但任务类型+平台在 SCENARIOS 中有定义（兼容性/安装卸载/界面适配）：返回该定义名称。
     * - 其余（如性能基础质量测试等）：按任务类型展示名 + 平台返回，如「性能基础质量测试(Android)」，保证列表「测试场景」列有值。
     */
    public String getScenarioDisplayNameOrFallback(Task task) {
        if (task == null) return null;
        Task.Platform platform = task.getPlatform();
        String scenarioKey = task.getScenarioKey();
        if (scenarioKey != null && !scenarioKey.isBlank() && platform != null) {
            return getScenarioDisplayName(scenarioKey.trim(), platform);
        }
        if (task.getTaskType() == Task.TaskType.FUNCTIONAL_QUALITY_TEST && platform != null) {
            return "未选择场景(" + platform.name() + ")";
        }
        // 无 scenarioKey 时，先尝试按任务类型+平台从 SCENARIOS 取展示名（兼容性/安装卸载/界面适配）
        Task.TaskType taskType = task.getTaskType();
        if (taskType != null && platform != null) {
            String catalogPl = catalogPlatformForScenarios(platform);
            String byType = SCENARIOS.stream()
                    .filter(s -> s.getTaskType() == taskType && platform.name().equals(s.getPlatform()))
                    .findFirst()
                    .map(ScenarioDefinition::getName)
                    .orElseGet(() -> SCENARIOS.stream()
                            .filter(s -> s.getTaskType() == taskType && catalogPl.equals(s.getPlatform()))
                            .findFirst()
                            .map(ScenarioDefinition::getName)
                            .orElse(null));
            if (byType != null) return byType;
        }
        // 性能基础质量测试等：用任务类型展示名 + 平台，保证「测试场景」列有显示
        if (taskType != null) {
            String typeName = taskType.getDisplayName();
            return platform != null ? typeName + "(" + platform.name() + ")" : typeName;
        }
        return null;
    }

    @Data
    public static class PlatformModuleGroup {
        private String platform;
        private String platformLabel;
        private List<ModuleGroup> modules;
    }

    @Data
    public static class ModuleGroup {
        private String moduleKey;
        private String moduleName;
        /** 子模块列表（即场景，子模块是做自动化的） */
        private List<ScenarioDefinition> scenarios;
    }

    @Data
    public static class ScenarioDefinition {
        private final String id;
        private final String name;
        private final String platform;
        private final Task.TaskType taskType;
        private final String description;
        /** 场景键：launch_app / login / search，执行时按此 + platform 分支 */
        private final String scenarioKey;
    }
}
