package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.UiFlowMapper;
import com.mobile.standalone.util.PlatformNames;
import com.mobile.standalone.util.RunTrack;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class UiFlowService {

    private final UiFlowMapper uiFlowMapper;
    private final com.mobile.standalone.mapper.UiElementMapper uiElementMapper;
    private final com.mobile.standalone.mapper.UiAppMapper uiAppMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * 补 env_bound 列。
     * <p>
     * <b>默认 1（绑定环境）</b>——存量流程一条都不能改行为。
     * 装包类流程本来就必须按环境隔离，默认成 0 会让线上回归突然把测试环境的装包流程也拉进来。
     */
    @jakarta.annotation.PostConstruct
    public void ensureSchema() {
        if (jdbcTemplate == null) {
            return;
        }
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ui_flows' AND COLUMN_NAME = 'env_bound'",
                    Integer.class);
            if (n != null && n == 0) {
                jdbcTemplate.execute("ALTER TABLE ui_flows ADD COLUMN env_bound TINYINT(1) NOT NULL DEFAULT 1 "
                        + "COMMENT '是否绑定环境：装包类必须绑，纯功能验证可不绑' AFTER environment");
            }
            Integer m = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ui_flows' AND COLUMN_NAME = 'release_stage'",
                    Integer.class);
            if (m != null && m == 0) {
                // 默认 ONLINE：存量流程本来就在线上回归里跑着，一条都不能掉出去
                jdbcTemplate.execute("ALTER TABLE ui_flows ADD COLUMN release_stage VARCHAR(16) NOT NULL DEFAULT 'ONLINE' "
                        + "COMMENT 'TESTING=本版新功能，线上包没有；ONLINE=已上线可常驻' AFTER env_bound");
            }
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ui_flows' AND COLUMN_NAME = 'is_core'",
                    Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("ALTER TABLE ui_flows ADD COLUMN is_core TINYINT(1) NOT NULL DEFAULT 0 "
                        + "COMMENT '核心流程：兼容性测试只跑这一批' AFTER release_stage");
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(UiFlowService.class)
                    .warn("[ui-flows] env_bound 列检查失败: {}", e.getMessage());
        }
    }

    public List<UiFlow> list(String appId, String platform, String catalog, Boolean componentOnly) {
        return list(appId, platform, catalog, componentOnly, null, null);
    }

    /** environment 传空＝不按环境过滤（列表页「全部环境」）；name 传空＝不过滤名称 */
    public List<UiFlow> list(String appId, String platform, String catalog,
                             Boolean componentOnly, String environment) {
        return list(appId, platform, catalog, componentOnly, environment, null);
    }

    /** name 按流程名称模糊匹配 */
    public List<UiFlow> list(String appId, String platform, String catalog,
                             Boolean componentOnly, String environment, String name) {
        return list(appId, platform, catalog, componentOnly, environment, name, null);
    }

    public List<UiFlow> list(String appId, String platform, String catalog,
                             Boolean componentOnly, String environment, String name, Boolean coreOnly) {
        return uiFlowMapper.findByFilters(blankToNull(appId), blankToNull(platform),
                blankToNull(catalog), componentOnly, blankToNull(environment), blankToNull(name), coreOnly);
    }

    /** 回归勾选列表：轨道为回归（ONLINE/SHARED）、启用、非组件，按 sort_order */
    public List<UiFlow> listForRegression(String appId, String platform) {
        return listForRegression(appId, platform, TestEnvironment.PROD);
    }

    /** 按环境取回归勾选列表；environment 传空按生产处理 */
    public List<UiFlow> listForRegression(String appId, String platform, String environment) {
        return listForBatch(appId, platform, environment, RunTrack.REGRESSION);
    }

    /** 某条轨道 + 某个环境的批量执行候选列表；两个参数都传空＝生产的回归，与加这两维之前一致 */
    public List<UiFlow> listForBatch(String appId, String platform, String environment, String track) {
        return listForBatch(appId, platform, environment, track, false);
    }

    /**
     * @param releasedPackage 跑的是<b>已发布的线上包</b>（上个版本），而不是本次待发布的 debug 包。
     *                        true 时只取 ONLINE 流程——本版新功能的流程在那个包里没有对应界面，
     *                        跑上去必然失败，而这种红跟质量无关，只会淹掉真问题。
     */
    public List<UiFlow> listForBatch(String appId, String platform, String environment,
                                     String track, boolean releasedPackage) {
        return uiFlowMapper.findForBatch(blankToNull(appId), blankToNull(platform),
                TestEnvironment.normalize(environment), RunTrack.catalogs(track), releasedPackage,
                RunTrack.isCompatibility(track));
    }

    /** 新建流程默认 TESTING：本版新写的流程，线上包里还没有对应功能 */
    public static String normalizeReleaseStage(String raw) {
        return "TESTING".equalsIgnoreCase(raw == null ? "" : raw.trim()) ? "TESTING" : "ONLINE";
    }

    /**
     * 哪些 catalog 属于回归轨道。
     * <p>
     * 跟环境无关——catalog 只表达「哪个入口用」，环境是独立的 {@code ui_flows.environment} 列。
     * 曾经想用 catalog 一个字段同时表达两件事，两种拆法都出过事：把 SHARED 当「两环境共用」，
     * 线上用例漏进了测试批次；把 AUTOMATION 当「测试环境回归」，自动化轨道被整个占掉、
     * 线上自动化用例没地方放。
     */
    public static List<String> regressionCatalogs() {
        return RunTrack.catalogs(RunTrack.REGRESSION);
    }

    public UiFlow get(String id) {
        return uiFlowMapper.findById(id);
    }

    /** 是否可作为线上回归用例执行 */
    public boolean isRegressionEligible(UiFlow flow) {
        return isRegressionEligible(flow, TestEnvironment.PROD);
    }

    /** 是否可作为该环境的回归用例执行 */
    public boolean isRegressionEligible(UiFlow flow, String environment) {
        return isBatchEligible(flow, environment, RunTrack.REGRESSION);
    }

    /** 能否进这条轨道、这个环境的批量执行：轨道、环境、启用、非组件，四条都要过 */
    public boolean isBatchEligible(UiFlow flow, String environment, String track) {
        return isBatchEligible(flow, environment, track, false);
    }

    /**
     * @param releasedPackage 跑已发布的线上包时，本版新功能的流程要排除掉——
     *                        那个包里根本没有对应界面
     */
    public boolean isBatchEligible(UiFlow flow, String environment, String track, boolean releasedPackage) {
        if (releasedPackage && flow != null && "TESTING".equalsIgnoreCase(
                flow.getReleaseStage() == null ? "ONLINE" : flow.getReleaseStage().trim())) {
            return false;
        }
        return isBatchEligibleIgnoringStage(flow, environment, track);
    }

    private boolean isBatchEligibleIgnoringStage(UiFlow flow, String environment, String track) {
        if (flow == null) return false;
        if (Boolean.FALSE.equals(flow.getEnabled())) return false;
        if (Boolean.TRUE.equals(flow.getIsComponent())) return false;
        String c = flow.getCatalog() != null ? flow.getCatalog().trim().toUpperCase() : "";
        if (!RunTrack.catalogs(track).contains(c)) return false;
        if (isEnvironmentAgnostic(flow)) return true;
        // 纯功能验证的流程不绑环境：验的是功能本身，跟连哪个后端无关。
        // 装包类必须绑（测试装蒲公英 debug 包、线上装渠道包，两套路径），所以只放开显式标了的
        if (Boolean.FALSE.equals(flow.getEnvBound())) return true;
        // 回归流程：迁移前建的行 environment 为空，按 PROD 解读，存量线上回归不受影响
        return TestEnvironment.normalize(flow.getEnvironment())
                .equals(TestEnvironment.normalize(environment));
    }

    /**
     * 自动化流程不绑环境。
     * <p>
     * 一条用例（「协议勾选框可勾选」）在测试包和线上包上是同一件事，
     * 它的自动化实现也该是同一条流程。环境差异只在「装哪个包、用哪套数据」——
     * 那是执行时的参数，不是流程的属性。绑了环境，同一个用例就得写两遍流程，
     * 而且一条用例要挂两条流程，关联关系立刻从 N:1 退化成 N:N。
     * <p>
     * 回归流程<b>照旧绑环境</b>：线上回归和测试回归本来就走不同的流程（装包方式、渠道都不同）。
     */
    public static boolean isEnvironmentAgnostic(UiFlow flow) {
        if (flow == null) {
            return false;
        }
        // 显式标了「不绑环境」的功能验证流程，和自动化流程一样跨环境可用
        return "AUTOMATION".equals(flow.getCatalog()) || Boolean.FALSE.equals(flow.getEnvBound());
    }

    /**
     * 流程是否归属该回归 APP。
     * 有 app_id 则精确匹配；空 app_id 的 SHARED 为通用；空 ONLINE 仅国内（历史兼容）。
     */
    public static boolean matchesRegressionApp(UiFlow flow, String appId) {
        if (flow == null || appId == null || appId.isBlank()) return false;
        String fa = flow.getAppId();
        if (fa != null && !fa.isBlank()) {
            return fa.equals(appId);
        }
        String c = flow.getCatalog() != null ? flow.getCatalog().trim().toUpperCase() : "";
        if ("SHARED".equals(c)) return true;
        if ("ONLINE".equals(c)) return "DOMESTIC_ESPORTS".equals(appId);
        return false;
    }

    @Transactional
    public UiFlow create(UiFlow flow) {
        if (flow.getName() == null || flow.getName().isBlank()) {
            throw new IllegalArgumentException("流程名称不能为空");
        }
        if (flow.getSteps() == null || flow.getSteps().isBlank()) {
            flow.setSteps("[]");
        }
        if (flow.getCatalog() == null || flow.getCatalog().isBlank()) {
            flow.setCatalog("SHARED");
        }
        flow.setEnvironment(TestEnvironment.normalize(flow.getEnvironment()));
        // release_stage 是 NOT NULL，而这里以前不兜底——调用方只要没显式设，
        // INSERT 就直接被数据库打回来（列上的 DEFAULT 救不了：INSERT 显式列出了这一列并传了 null，
        // DEFAULT 只在整列被省略时才生效）。上面那些字段个个都兜了底，唯独漏了它
        flow.setReleaseStage(normalizeReleaseStage(flow.getReleaseStage()));
        // 默认绑环境：装包类误标成不绑会把测试 debug 包装进线上回归
        if (flow.getEnvBound() == null) flow.setEnvBound(true);
        if (flow.getCore() == null) flow.setCore(false);
        if (flow.getIsComponent() == null) flow.setIsComponent(false);
        if (flow.getEnabled() == null) flow.setEnabled(true);
        if (flow.getSortOrder() == null) flow.setSortOrder(100);
        flow.setId("uif-" + UUID.randomUUID());
        flow.setName(flow.getName().trim());
        // 署名由服务端盖，<b>覆盖掉请求体里传进来的值</b>。
        // 这个字段原先是 @RequestBody 里的一个普通字段，谁都能填任何名字——署名等于没有，
        // 而且伪造没有任何痕迹。取不到登录人（定时任务、组件复制）时留 null，
        // 比编一个名字诚实
        flow.setCreatedBy(com.mobile.standalone.auth.CurrentUser.nameOrNull());
        validateDependencies(flow);
        LocalDateTime now = LocalDateTime.now();
        flow.setCreatedAt(now);
        flow.setUpdatedAt(now);
        uiFlowMapper.insert(flow);
        return flow;
    }

    /**
     * 复制一条流程：步骤原样带走，名字加「副本」，新 id。
     * 用例关联不复制——那是另一条流程的绑定，拷过去容易人以为两边还连着。
     */
    @Transactional
    public UiFlow copy(String id) {
        UiFlow src = uiFlowMapper.findById(id);
        if (src == null) {
            return null;
        }
        UiFlow copy = new UiFlow();
        copy.setName(uniqueCopyName(src.getName()));
        copy.setDescription(src.getDescription());
        copy.setAppId(src.getAppId());
        copy.setPlatform(src.getPlatform());
        copy.setCatalog(src.getCatalog());
        copy.setEnvironment(src.getEnvironment());
        copy.setEnvBound(src.getEnvBound());
        copy.setReleaseStage(src.getReleaseStage());
        copy.setIsComponent(src.getIsComponent());
        copy.setSteps(src.getSteps());
        copy.setDependsOn(src.getDependsOn());
        copy.setEnabled(src.getEnabled());
        copy.setSortOrder(src.getSortOrder());
        return create(copy);
    }

    /**
     * 跨平台克隆：按一条流程生成另一平台（如 Android → iOS）的流程。
     * <p>
     * 元素引用大体带走，但有两类必须特殊处理：
     * <ul>
     *   <li>启动类步骤里的 packageName/activity（安卓包 ≠ iOS BundleId）</li>
     *   <li>源流程里的「幽灵 elementKey」——编排编了个库里根本没有的名字，原样拷到
     *       目标端只会变成更难查的失败。若两端都有唯一近似的 ACTIVE 元素，克隆时改写</li>
     * </ul>
     * 同时给出覆盖报告：已就绪 / 待定位 / 缺失 / 源也没有 / 已自动纠正。
     */
    @Transactional
    public PlatformCloneResult cloneToPlatform(String id, String targetPlatform, String environment) {
        UiFlow src = uiFlowMapper.findById(id);
        if (src == null) {
            return null;
        }
        String platform = PlatformNames.normalize(targetPlatform);
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("目标平台不能为空（如 IOS / ANDROID）");
        }
        String srcPlatform = PlatformNames.normalize(src.getPlatform());
        String steps = src.getSteps();
        if (srcPlatform != null && !srcPlatform.equalsIgnoreCase(platform)) {
            steps = rewriteLaunchParamsForPlatform(steps, platform, src.getAppId());
        }

        java.util.List<String> keys = elementKeysFromSteps(steps).stream()
                .filter(k -> k != null && !k.isBlank()).map(String::trim).distinct().toList();
        java.util.LinkedHashMap<String, String> remapped = new java.util.LinkedHashMap<>();
        java.util.List<String> sourceMissing = new java.util.ArrayList<>();
        java.util.List<String> targetActiveNames = listActiveNameEns(src.getAppId(), platform);
        for (String key : keys) {
            if (findActiveOnPlatform(src.getAppId(), platform, key) != null
                    || findAnyOnPlatform(src.getAppId(), platform, key) != null) {
                continue;
            }
            // 目标没有同名。若源端也没有 → 编排幽灵引用，试着纠到唯一近似名
            boolean sourceHas = srcPlatform != null
                    && (findActiveOnPlatform(src.getAppId(), srcPlatform, key) != null
                    || findAnyOnPlatform(src.getAppId(), srcPlatform, key) != null);
            if (!sourceHas) {
                sourceMissing.add(key);
                String guess = uniqueSimilarName(key, targetActiveNames);
                // 近似名在源端也必须是 ACTIVE，避免「猜到一个只在 iOS 有的无关元素」
                if (guess != null && srcPlatform != null
                        && findActiveOnPlatform(src.getAppId(), srcPlatform, guess) != null) {
                    remapped.put(key, guess);
                }
            }
        }
        if (!remapped.isEmpty()) {
            steps = rewriteElementKeys(steps, remapped);
            log.info("跨端克隆纠正幽灵元素引用 {}: {}", src.getId(), remapped);
        }

        UiFlow copy = new UiFlow();
        copy.setName(platformCloneName(src.getName(), platform));
        copy.setDescription(src.getDescription());
        copy.setAppId(src.getAppId());
        copy.setPlatform(platform);
        copy.setCatalog(src.getCatalog());
        copy.setEnvironment(environment != null && !environment.isBlank()
                ? environment.trim() : src.getEnvironment());
        copy.setEnvBound(src.getEnvBound());
        copy.setReleaseStage(src.getReleaseStage());
        copy.setIsComponent(src.getIsComponent());
        copy.setSteps(steps);
        copy.setDependsOn(src.getDependsOn());
        copy.setEnabled(src.getEnabled());
        copy.setSortOrder(src.getSortOrder());
        UiFlow saved = create(copy);

        java.util.List<String> finalKeys = elementKeysFromSteps(steps).stream()
                .filter(k -> k != null && !k.isBlank()).map(String::trim).distinct().toList();
        java.util.List<String> ready = new java.util.ArrayList<>();
        java.util.List<String> needLocator = new java.util.ArrayList<>();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String key : finalKeys) {
            if (findActiveOnPlatform(src.getAppId(), platform, key) != null) {
                ready.add(key);
            } else if (findAnyOnPlatform(src.getAppId(), platform, key) != null) {
                needLocator.add(key);
            } else {
                missing.add(key);
            }
        }
        // 源幽灵里已自动纠正的，不再当「源缺失」吓人
        java.util.List<String> unresolvedSourceMissing = sourceMissing.stream()
                .filter(k -> !remapped.containsKey(k))
                .toList();
        java.util.List<String> components = componentIdsOf(src).stream()
                .filter(k -> k != null && !k.isBlank()).map(String::trim).distinct().toList();
        return new PlatformCloneResult(saved, finalKeys.size(), ready, needLocator, missing, components,
                unresolvedSourceMissing, remapped);
    }

    /**
     * 跨平台克隆结果。
     *
     * @param sourceMissing 源平台也没有的 elementKey（编排幽灵引用，且未能自动纠正）
     * @param remapped      克隆时自动纠正的 old→new（两端都有的唯一近似 ACTIVE 元素）
     */
    public record PlatformCloneResult(UiFlow flow, int elementCount,
                                      java.util.List<String> ready,
                                      java.util.List<String> needLocator,
                                      java.util.List<String> missing,
                                      java.util.List<String> referencedComponents,
                                      java.util.List<String> sourceMissing,
                                      java.util.Map<String, String> remapped) {
    }

    private com.mobile.standalone.entity.UiElement findActiveOnPlatform(String appId, String platform, String key) {
        com.mobile.standalone.entity.UiElement el = uiElementMapper.findActiveByKey(appId, platform, key);
        if (el != null) return el;
        for (String legacy : PlatformNames.legacyAliasesOf(platform)) {
            el = uiElementMapper.findActiveByKey(appId, legacy, key);
            if (el != null) return el;
        }
        return null;
    }

    private com.mobile.standalone.entity.UiElement findAnyOnPlatform(String appId, String platform, String key) {
        com.mobile.standalone.entity.UiElement el = uiElementMapper.findByAppPlatformKey(appId, platform, key, null);
        if (el != null) return el;
        for (String legacy : PlatformNames.legacyAliasesOf(platform)) {
            el = uiElementMapper.findByAppPlatformKey(appId, legacy, key, null);
            if (el != null) return el;
        }
        return null;
    }

    /**
     * 跨端克隆时改写启动相关参数：packageName → 目标端包名/BundleId，iOS 去掉 activity。
     * <p>
     * 步骤里若显式写了安卓包名，运行时 {@code resolvePackage} 会优先用它，永远走不到应用字典里的
     * iOS BundleId——这正是「复制到 iOS 后冷启动失败」的根因。
     */
    String rewriteLaunchParamsForPlatform(String stepsJson, String targetPlatform, String flowAppId) {
        com.fasterxml.jackson.databind.JsonNode root = parseStepsTree(stepsJson);
        if (root == null) {
            return stepsJson == null ? "[]" : stepsJson;
        }
        boolean ios = PlatformNames.isIos(targetPlatform);
        walkAndRewriteLaunchParams(root, ios, flowAppId);
        try {
            return com.mobile.standalone.util.ExecutionLogJson.mapper().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("跨端克隆改写启动参数失败，回退原步骤: {}", e.getMessage());
            return stepsJson;
        }
    }

    private void walkAndRewriteLaunchParams(com.fasterxml.jackson.databind.JsonNode node,
                                            boolean ios, String flowAppId) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> walkAndRewriteLaunchParams(child, ios, flowAppId));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        com.fasterxml.jackson.databind.JsonNode params = node.get("params");
        if (params != null && params.isObject() && params instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
            boolean touchesLaunch = obj.has("packageName") || obj.has("activity") || obj.has("appId");
            String kw = textNode(node, "keywordCode");
            if (kw != null) {
                String u = kw.toUpperCase(java.util.Locale.ROOT);
                if (u.contains("START") || u.contains("LAUNCH") || u.contains("INSTALL")
                        || u.contains("UNINSTALL") || u.contains("KILL") || u.contains("HOT_START")
                        || u.contains("COLD_START") || u.contains("OPEN_H5")) {
                    touchesLaunch = true;
                }
            }
            if (touchesLaunch) {
                String appId = textNode(obj, "appId");
                if (appId == null) {
                    appId = flowAppId;
                }
                String pkg = packageForPlatform(appId, ios);
                if (pkg == null || pkg.isBlank()) {
                    // 字典没有目标端包名：删掉源端写死的，逼运行时/人再填，总比带着错包名启动强
                    obj.remove("packageName");
                } else {
                    obj.put("packageName", pkg);
                }
                if (ios) {
                    obj.remove("activity");
                }
            }
        }
        node.fields().forEachRemaining(e -> walkAndRewriteLaunchParams(e.getValue(), ios, flowAppId));
    }

    private String packageForPlatform(String appId, boolean ios) {
        if (appId == null || appId.isBlank() || uiAppMapper == null) {
            return null;
        }
        com.mobile.standalone.entity.UiApp app = uiAppMapper.findById(appId.trim());
        if (app == null) {
            return null;
        }
        String pkg = ios ? app.getIosBundleId() : app.getAndroidPackage();
        return pkg == null || pkg.isBlank() ? null : pkg.trim();
    }

    private String platformCloneName(String raw, String platform) {
        String base = (raw == null || raw.isBlank()) ? "未命名流程" : raw.trim();
        String tag = "(" + platformLabel(platform) + ")";
        return base.endsWith(tag) ? base : base + " " + tag;
    }

    private static String platformLabel(String platform) {
        if (platform == null) return "副本";
        String p = platform.toUpperCase(java.util.Locale.ROOT);
        if (p.contains("IOS")) return "iOS";
        if (p.contains("ANDROID")) return "Android";
        if (p.contains("HARMONY")) return "鸿蒙";
        return platform;
    }

    private String uniqueCopyName(String raw) {
        String base = (raw == null || raw.isBlank()) ? "未命名流程" : raw.trim();
        // 名字不强制唯一，只保证一眼能看出是副本；连点两次就自然变成「… 副本 副本」
        if (base.endsWith(" 副本")) {
            return base;
        }
        return base + " 副本";
    }

    @Transactional
    public UiFlow update(String id, UiFlow patch) {
        UiFlow existing = uiFlowMapper.findById(id);
        if (existing == null) return null;
        if (patch.getName() != null) existing.setName(patch.getName().trim());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getAppId() != null) existing.setAppId(patch.getAppId());
        if (patch.getPlatform() != null) existing.setPlatform(patch.getPlatform());
        if (patch.getCatalog() != null) existing.setCatalog(patch.getCatalog());
        if (patch.getEnvironment() != null) {
            existing.setEnvironment(TestEnvironment.normalize(patch.getEnvironment()));
        }
        if (patch.getEnvBound() != null) existing.setEnvBound(patch.getEnvBound());
        if (patch.getReleaseStage() != null) {
            existing.setReleaseStage(normalizeReleaseStage(patch.getReleaseStage()));
        }
        if (patch.getCore() != null) existing.setCore(patch.getCore());
        if (patch.getIsComponent() != null) existing.setIsComponent(patch.getIsComponent());
        if (patch.getSteps() != null) existing.setSteps(patch.getSteps());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getSortOrder() != null) existing.setSortOrder(patch.getSortOrder());
        if (patch.getDependsOn() != null) existing.setDependsOn(patch.getDependsOn());
        validateDependencies(existing);
        existing.setUpdatedAt(LocalDateTime.now());
        uiFlowMapper.update(existing);
        return existing;
    }

    /**
     * 保存前校验前置依赖。
     * <p>
     * 环、指向不存在的流程、跨环境依赖，全在这里拦住。留到执行期只会在跑了一半时炸，
     * 而且 {@code CALL_COMPONENT} 的前车之鉴摆着：成环时它报的是「嵌套过深」，
     * 看到那句话的人根本想不到是自己配了个环。
     */
    void validateDependencies(UiFlow flow) {
        List<String> deps = FlowDependencyResolver.dependenciesOf(flow);
        if (deps.isEmpty()) {
            return;
        }
        String env = TestEnvironment.normalize(flow.getEnvironment());
        for (String depId : deps) {
            UiFlow dep = uiFlowMapper.findById(depId);
            if (dep == null) {
                throw new IllegalArgumentException("前置流程不存在: " + depId);
            }
            if (Boolean.TRUE.equals(dep.getIsComponent())) {
                throw new IllegalArgumentException(
                        "「" + dep.getName() + "」是组件，不能当前置流程。组件用「调用组件」步骤内联执行，"
                                + "前置流程是批量执行里单独跑一次的用例，两者不是一回事");
            }
            if (!TestEnvironment.normalize(dep.getEnvironment()).equals(env)) {
                throw new IllegalArgumentException(
                        "前置流程「" + dep.getName() + "」是" + TestEnvironment.label(dep.getEnvironment())
                                + "的用例，本流程是" + TestEnvironment.label(env)
                                + "。跨环境依赖跑起来必然拿错包或错账号");
            }
        }
        // 环检测要连上整张图，不能只看直接依赖：A→B→C→A 三条边单看都合法
        FlowDependencyResolver.resolve(List.of(flow.getId()), id ->
                id.equals(flow.getId()) ? flow : uiFlowMapper.findById(id));
    }

    @Transactional
    public boolean delete(String id) {
        return uiFlowMapper.deleteById(id) > 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 执行时还要人填的定位变量名（编排步骤里已经写好的不算）。
     * <p>
     * 元素定位可以写 {@code ${mall_category_tab}}，真正取值常在步骤参数里
     * （同一步填「刀塔商城」、另一步填「CS商城」）。执行器会按步合并参数，
     * 所以编排里已填的变量不应再弹执行对话框逼人填一遍。
     * <p>
     * 只返回「至少有一步用到该变量、且该步 params 没给值」的名字。
     * 取不全也不致命——真缺时执行会当场报出缺哪个。
     */
    public java.util.List<String> collectLocatorVariables(UiFlow flow) {
        java.util.LinkedHashSet<String> needed = new java.util.LinkedHashSet<>();
        collectUnresolvedVars(flow, needed, new java.util.HashSet<>(), 0);
        return new java.util.ArrayList<>(needed);
    }

    private void collectUnresolvedVars(UiFlow flow, java.util.Set<String> needed,
                                       java.util.Set<String> visited, int depth) {
        // 组件可以互相引用，没有环检测会栈溢出
        if (flow == null || depth > 5 || !visited.add(flow.getId())) {
            return;
        }
        com.fasterxml.jackson.databind.JsonNode root = parseStepsTree(flow.getSteps());
        if (root == null) {
            return;
        }
        walkStepTreeForUnresolvedVars(root, flow, needed, visited, depth);
    }

    private void walkStepTreeForUnresolvedVars(com.fasterxml.jackson.databind.JsonNode node,
                                               UiFlow flow, java.util.Set<String> needed,
                                               java.util.Set<String> visited, int depth) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> walkStepTreeForUnresolvedVars(child, flow, needed, visited, depth));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        String elementKey = textNode(node, "elementKey");
        if (elementKey == null) {
            elementKey = textNode(node, "element");
        }
        com.fasterxml.jackson.databind.JsonNode params = node.get("params");
        if (elementKey != null) {
            for (String var : varsOfElement(flow, elementKey)) {
                if (!paramFilled(params, var)) {
                    needed.add(var);
                }
            }
        }

        String childFlowId = textNode(node, "componentId");
        if (childFlowId == null) {
            childFlowId = textNode(node, "flowId");
        }
        if (childFlowId == null && params != null && params.isObject()) {
            childFlowId = textNode(params, "componentId");
            if (childFlowId == null) {
                childFlowId = textNode(params, "flowId");
            }
        }
        if (childFlowId != null) {
            java.util.LinkedHashSet<String> childNeeded = new java.util.LinkedHashSet<>();
            collectUnresolvedVars(get(childFlowId), childNeeded, visited, depth + 1);
            for (String var : childNeeded) {
                if (!paramFilled(params, var)) {
                    needed.add(var);
                }
            }
        }

        // 嵌套分支 / 子步骤：thenSteps、elseSteps、以及 params 里其它对象/数组
        node.fields().forEachRemaining(e -> {
            String k = e.getKey();
            if ("params".equals(k)) {
                walkStepTreeForUnresolvedVars(e.getValue(), flow, needed, visited, depth);
            } else if (e.getValue() != null && (e.getValue().isArray() || e.getValue().isObject())) {
                // 步骤根上偶尔也会挂嵌套步骤数组
                if (e.getValue().isArray() || looksLikeStepObject(e.getValue())) {
                    walkStepTreeForUnresolvedVars(e.getValue(), flow, needed, visited, depth);
                }
            }
        });
    }

    private java.util.List<String> varsOfElement(UiFlow flow, String elementKey) {
        if (elementKey == null || elementKey.isBlank()) {
            return java.util.List.of();
        }
        com.mobile.standalone.entity.UiElement el = uiElementMapper.findActiveByKey(
                flow.getAppId(),
                com.mobile.standalone.util.PlatformNames.normalize(flow.getPlatform()),
                elementKey.trim());
        if (el == null) {
            return java.util.List.of();
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        names.addAll(com.mobile.standalone.util.LocatorTemplate.variablesIn(el.getLocatorValue()));
        names.addAll(com.mobile.standalone.util.LocatorTemplate.variablesIn(el.getLocatorFallbacks()));
        return new java.util.ArrayList<>(names);
    }

    private static boolean paramFilled(com.fasterxml.jackson.databind.JsonNode params, String name) {
        if (params == null || !params.isObject() || name == null) {
            return false;
        }
        com.fasterxml.jackson.databind.JsonNode v = params.get(name);
        return v != null && v.isValueNode() && !v.isNull() && !v.asText("").trim().isEmpty();
    }

    private static String textNode(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean looksLikeStepObject(com.fasterxml.jackson.databind.JsonNode n) {
        return n != null && n.isObject()
                && (n.has("keywordCode") || n.has("elementKey") || n.has("element") || n.has("params"));
    }

    private static com.fasterxml.jackson.databind.JsonNode parseStepsTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return com.mobile.standalone.util.ExecutionLogJson.mapper().readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 步骤 JSON 里所有 elementKey，含嵌套分支（跨平台克隆等用） */
    private java.util.List<String> elementKeysOf(UiFlow flow) {
        return elementKeysFromSteps(flow == null ? null : flow.getSteps());
    }

    private java.util.List<String> elementKeysFromSteps(String stepsJson) {
        java.util.List<String> out = new java.util.ArrayList<>();
        scanJson(stepsJson, "elementKey", out);
        scanJson(stepsJson, "element", out);
        return out;
    }

    private java.util.List<String> listActiveNameEns(String appId, String platform) {
        if (appId == null || platform == null || uiElementMapper == null) {
            return java.util.List.of();
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        java.util.List<com.mobile.standalone.entity.UiElement> rows = uiElementMapper.findByFilters(
                appId, platform, null, null, null, null, null, "ACTIVE", null, null, null);
        if (rows != null) {
            for (com.mobile.standalone.entity.UiElement el : rows) {
                if (el.getNameEn() != null && !el.getNameEn().isBlank()) {
                    names.add(el.getNameEn().trim());
                }
            }
        }
        for (String legacy : PlatformNames.legacyAliasesOf(platform)) {
            rows = uiElementMapper.findByFilters(
                    appId, legacy, null, null, null, null, null, "ACTIVE", null, null, null);
            if (rows == null) continue;
            for (com.mobile.standalone.entity.UiElement el : rows) {
                if (el.getNameEn() != null && !el.getNameEn().isBlank()) {
                    names.add(el.getNameEn().trim());
                }
            }
        }
        return new java.util.ArrayList<>(names);
    }

    /**
     * 在候选 ACTIVE 名里找与幽灵 key 唯一近似的一个。
     * 词重叠 ≥3（按下划线分词）且只有一个最高分，才敢自动改——猜错比不改更糟。
     */
    static String uniqueSimilarName(String ghost, java.util.List<String> candidates) {
        if (ghost == null || ghost.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        java.util.Set<String> ghostTokens = nameTokens(ghost);
        if (ghostTokens.size() < 2) {
            return null;
        }
        String best = null;
        int bestScore = 0;
        boolean tie = false;
        for (String c : candidates) {
            if (c == null || c.isBlank() || c.equals(ghost)) continue;
            int score = overlapCount(ghostTokens, nameTokens(c));
            if (score < 3) continue;
            if (score > bestScore) {
                bestScore = score;
                best = c;
                tie = false;
            } else if (score == bestScore) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    static java.util.Set<String> nameTokens(String nameEn) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (nameEn == null) return out;
        for (String p : nameEn.trim().toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (p.length() >= 2) out.add(p);
        }
        return out;
    }

    static int overlapCount(java.util.Set<String> a, java.util.Set<String> b) {
        int n = 0;
        for (String t : a) {
            if (b.contains(t)) n++;
        }
        return n;
    }

    private static String rewriteElementKeys(String stepsJson, java.util.Map<String, String> remapped) {
        if (stepsJson == null || remapped == null || remapped.isEmpty()) {
            return stepsJson;
        }
        com.fasterxml.jackson.databind.JsonNode root = parseStepsTree(stepsJson);
        if (root == null) {
            return stepsJson;
        }
        walkReplaceElementKeys(root, remapped);
        try {
            return com.mobile.standalone.util.ExecutionLogJson.mapper().writeValueAsString(root);
        } catch (Exception e) {
            return stepsJson;
        }
    }

    private static void walkReplaceElementKeys(com.fasterxml.jackson.databind.JsonNode node,
                                               java.util.Map<String, String> remapped) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(c -> walkReplaceElementKeys(c, remapped));
            return;
        }
        if (!node.isObject()) return;
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
            for (String field : java.util.List.of("elementKey", "element")) {
                com.fasterxml.jackson.databind.JsonNode v = obj.get(field);
                if (v != null && v.isTextual()) {
                    String next = remapped.get(v.asText().trim());
                    if (next != null) obj.put(field, next);
                }
            }
        }
        node.fields().forEachRemaining(e -> walkReplaceElementKeys(e.getValue(), remapped));
    }

    private java.util.List<String> componentIdsOf(UiFlow flow) {
        java.util.List<String> out = new java.util.ArrayList<>();
        scanJson(flow.getSteps(), "componentId", out);
        scanJson(flow.getSteps(), "flowId", out);
        return out;
    }

    /** 步骤结构嵌套多变（分支、竞速、组件），按字段名整树扫比逐层解析稳 */
    private void scanJson(String json, String field, java.util.List<String> out) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    com.mobile.standalone.util.ExecutionLogJson.mapper().readTree(json);
            java.util.Deque<com.fasterxml.jackson.databind.JsonNode> stack = new java.util.ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode n = stack.pop();
                if (n.isObject()) {
                    com.fasterxml.jackson.databind.JsonNode v = n.get(field);
                    if (v != null && v.isTextual() && !v.asText().isBlank()) {
                        out.add(v.asText().trim());
                    }
                    n.fields().forEachRemaining(e -> stack.push(e.getValue()));
                } else if (n.isArray()) {
                    n.forEach(stack::push);
                }
            }
        } catch (Exception ignored) {
            // 扫不出来就当没有
        }
    }

    /**
     * 检查这条流程能不能换个环境跑。
     * <p>
     * 「流程共用、数据不共用」的具体形态就是手机号、赛事 ID 敲死在了步骤参数里——
     * 测试环境那个号线上不存在，同一条流程换个环境跑就挂在第一步。这里只报不改：
     * 参数里那串数字是手机号还是订单号只有人知道，猜错了会把能跑的流程改坏，
     * 而且坏得很隐蔽（跑得通，但验的不是那回事）。
     */
    public java.util.List<FlowDataScanner.Hit> scanEnvData(String flowId) {
        UiFlow flow = get(flowId);
        java.util.List<FlowDataScanner.Hit> hits = new java.util.ArrayList<>();
        if (flow == null || flow.getSteps() == null || flow.getSteps().isBlank()) {
            return hits;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    com.mobile.standalone.util.ExecutionLogJson.mapper().readTree(flow.getSteps());
            walkStepsForData(root, "", hits);
        } catch (Exception e) {
            log.warn("扫描流程数据失败 flowId={}", flowId, e);
        }
        // 组件里的写死数据一样会挡住换环境，跟着一起报
        for (String childId : componentIdsOf(flow)) {
            if (childId == null || childId.equals(flowId)) continue;
            for (FlowDataScanner.Hit h : scanEnvData(childId)) {
                h.setStepNo("组件 " + childId + " · " + h.getStepNo());
                hits.add(h);
            }
        }
        return hits;
    }

    /** 步骤结构嵌套多变（分支、竞速、组件），整树扫比逐层解析稳 */
    private void walkStepsForData(com.fasterxml.jackson.databind.JsonNode node, String stepNo,
                                  java.util.List<FlowDataScanner.Hit> out) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            int i = 1;
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                String no = stepNo.isEmpty() ? String.valueOf(i) : stepNo + "." + i;
                walkStepsForData(child, no, out);
                i++;
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        com.fasterxml.jackson.databind.JsonNode params = node.get("params");
        if (params != null && params.isObject()) {
            params.fields().forEachRemaining(e -> {
                if (e.getValue().isTextual()) {
                    out.addAll(FlowDataScanner.scan(stepNo, e.getKey(), e.getValue().asText()));
                }
            });
        }
        // 按文案定位的步骤换环境也会挂——那条新闻/赛事在另一边不存在
        String locType = text(node, "locatorType");
        String locVal = text(node, "locatorValue");
        String advice = FlowDataScanner.rewriteAdviceFor(locType, locVal);
        if (advice != null) {
            FlowDataScanner.Hit h = new FlowDataScanner.Hit();
            h.setStepNo(stepNo);
            h.setParamName("定位方式");
            h.setValue(locVal);
            h.setKind("LOCATOR");
            h.setReason(advice);
            out.add(h);
        }
        node.fields().forEachRemaining(e -> {
            if (!"params".equals(e.getKey()) && (e.getValue().isArray() || e.getValue().isObject())) {
                walkStepsForData(e.getValue(), stepNo, out);
            }
        });
    }

    private String text(com.fasterxml.jackson.databind.JsonNode n, String field) {
        com.fasterxml.jackson.databind.JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

}
