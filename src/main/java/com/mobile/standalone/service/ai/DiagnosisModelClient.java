package com.mobile.standalone.service.ai;

import com.mobile.standalone.dto.FailureDiagnosisDtos.Verdict;

import java.nio.file.Path;
import java.util.List;

/**
 * 归因模型调用。
 * <p>
 * 抽成接口是因为「判得准不准」要靠真实样本才知道，换模型甚至换供应商是可预期的事——
 * 换的时候只该动这一层，上下文组装、结论存储、驱动动作都不该跟着改。
 */
public interface DiagnosisModelClient {

    /** 配置齐了才算可用，没配 key 时整条归因链路静默跳过 */
    boolean isConfigured();

    /** 当前模型标识，写进结论里便于事后对比不同模型的准确率 */
    String modelId();

    /**
     * @param prompt     渲染好的提示词全文
     * @param screenshots 现场截图，实现方负责压缩后再上传
     */
    Verdict diagnose(String prompt, List<Path> screenshots) throws Exception;
}
