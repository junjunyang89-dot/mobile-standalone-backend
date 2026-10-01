package com.mobile.standalone.service.apitest;

/**
 * 这条用例<b>为什么存在</b>——决定它什么时候跑、什么时候该退役。
 *
 * <p>注意它和已有两个字段是<b>三个正交的轴</b>,不要混:
 * <ul>
 *   <li>{@code environment}(TEST/PROD/ANY)—— 在<b>哪儿</b>跑</li>
 *   <li>{@code testOnly} —— <b>能不能</b>在线上跑(会写数据的那类)</li>
 *   <li>本枚举 —— 为什么存在,<b>活多久</b></li>
 * </ul>
 *
 * <h2>为什么必须存下来,不能推导</h2>
 *
 * <p>看起来可以推:有 {@code sourceBranch} 且不是 master 的就是分支用例。
 * 但分支一合进 master,推导的结果就会<b>自己从 BRANCH 翻成 REGRESSION</b>——
 * 而那一刻恰恰是唯一需要人做决定的时刻:
 *
 * <ul>
 *   <li><b>退役</b>——它验的是这次改动的特定行为,以后不再有意义</li>
 *   <li><b>升级成回归用例</b>——它验的是新增的稳定能力,该长期守着</li>
 * </ul>
 *
 * <p>推导会把这个决策点<b>静默地消掉</b>:所有分支用例在合并那天自动变成"回归用例",
 * 没人看过、没人决定过,然后永远留在用例库里。
 * <b>用例库腐烂的主要方式不是写得不够多,是写完没人清。</b>
 * 跑三百条、红了没人管,整个平台就成了摆设。
 *
 * <p>所以"还没分诊"这件事本身必须是可见的,而它正好不需要额外字段:
 * <b>分支已经合进 master、而这条用例还是 BRANCH</b> —— 这就是待分诊队列。
 * 人的动作(改成 REGRESSION,或者 enabled=false)就是分诊记录本身。
 *
 * <h2>为什么有第三个状态</h2>
 *
 * <p>这个字段是后加的,库里已有的用例没人标过。把它们一律当成回归用例是个猜测,
 * 而猜错的那些就是上面说的"永远不退役"的垃圾;一律当成分支用例则会把
 * 全部历史用例倒进待分诊队列——量一大人就只会批量点过,比不分诊更糟。
 *
 * <p>所以 {@link #UNCLASSIFIED} 是"确实不知道",而且它的倒向是<b>保守于覆盖</b>:
 * 执行上按回归对待(不因为加了个字段就悄悄少跑用例),但在清单上显式标出来,
 * 可以筛出来慢慢分。
 *
 * <h2>通过率不能共用分母</h2>
 *
 * <p>分支用例挂了意味着"这次改动有问题"(该找开发),回归用例挂了意味着
 * "回归了"(该拉警报)。混进一个分母后"通过率 92%"就没有任何含义——
 * 你不知道那 8% 该找谁。所以统计一律按 {@link #passRateBucket()} 分开算。
 *
 * <p>这和 BLOCKED 不进分母是同一条原则:<b>把语义不同的结果混进一个数字,
 * 那个数字就不再能支撑任何决定。</b>
 */
public enum ApiCaseKind {

    /** 为某次提测写的,寿命是一个提测周期。合并上线后必须分诊 */
    BRANCH("分支用例", "为某次提测写的。挂了＝这次改动有问题。"
            + "对应分支合并上线后必须分诊:退役,或升级成回归用例"),

    /** 长期守护已有行为 */
    REGRESSION("回归用例", "长期守已有行为。挂了＝回归了"),

    /** 加这个字段之前就存在的用例,没人标过 */
    UNCLASSIFIED("未分类", "加这个字段之前就有的用例。"
            + "执行上按回归对待(不少跑),但没人确认过它该不该长期留着");

    private final String label;
    private final String description;

    ApiCaseKind(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /**
     * 严格解析:空、空白、不认识的值返回 {@code null}。
     *
     * <p>存在的理由是:"认不出来"这件事在不同场合要有不同处理,
     * 而只有调用方知道该怎么处理:
     * <ul>
     *   <li><b>存用例</b>({@link #of}):归到 UNCLASSIFIED。
     *       一个拼错的值不该让整条用例写不进去</li>
     *   <li><b>筛列表</b>:抛。当成"不筛"的话,界面会把全部用例
     *       显示成某一类,人按这个去分诊</li>
     *   <li><b>折叠运行分桶</b>:归到"没记录"。
     *       混进回归桶的话,它会悄悄进到回归通过率的分母里</li>
     * </ul>
     * 三处都自己写一遍 for 循环的话,将来加一个枚举值会漏掉其中一两处,
     * 而漏掉的表现是那个新值被当成"认不出来"——不报错。
     */
    public static ApiCaseKind strict(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (ApiCaseKind k : values()) {
            if (k.name().equals(s)) {
                return k;
            }
        }
        return null;
    }

    /**
     * 宽松解析:空、空白、不认识的值一律 {@link #UNCLASSIFIED}。
     *
     * <p>不抛异常是故意的——这个字段来自数据库和前端,
     * 一个拼错的值不该让整条用例列表打不开。而落到 UNCLASSIFIED
     * 的后果是"这条用例显示未分类",看得见,有人会来问。
     *
     * <p><b>只适合用在"存"的路径上。</b>筛选和分桶都需要区分
     * "认不出来"和"未分类",那两处用 {@link #strict}。
     */
    public static ApiCaseKind of(String raw) {
        ApiCaseKind k = strict(raw);
        return k == null ? UNCLASSIFIED : k;
    }

    /**
     * 定期回归要不要跑它。
     *
     * <p>{@link #UNCLASSIFIED} 返回 {@code true}:加字段不该让已有用例悄悄少跑。
     * 少跑是静默的——覆盖掉了没人会发现。
     */
    public boolean runsInPeriodicRegression() {
        return this != BRANCH;
    }

    /**
     * 对应分支合并后,这条用例需不需要人来分诊。
     *
     * <p>只有 BRANCH 需要。UNCLASSIFIED 不进这个队列——它没有对应分支,
     * 进来了也无从判断,只会把队列冲淡到没人看。
     */
    public boolean needsTriageAfterMerge() {
        return this == BRANCH;
    }

    /**
     * 通过率分桶。<b>不同桶的结果绝不能算进同一个分母。</b>
     *
     * <p>做成方法而不是写在注释里,是因为注释挡不住下一个人直接
     * {@code count(passed)/count(all)}。
     *
     * <p>UNCLASSIFIED 和 REGRESSION 同桶:它们在执行上就是按回归跑的,
     * 跑了却把结果从分母里摘掉,等于把失败藏起来——那比归错桶更糟。
     */
    public String passRateBucket() {
        return this == BRANCH ? "BRANCH" : "REGRESSION";
    }
}
