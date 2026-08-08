package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.TaskFailureDiagnosis;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskFailureDiagnosisMapper {

    /** 按写入时间倒序，最新的在前 */
    List<TaskFailureDiagnosis> findByTaskId(@Param("taskId") String taskId);

    TaskFailureDiagnosis findById(@Param("id") String id);

    int insert(TaskFailureDiagnosis diagnosis);

    /** 近期同签名的 AI 结论，用于复用而不是重复调模型 */
    TaskFailureDiagnosis findLatestBySignature(@Param("signature") String signature,
                                               @Param("since") java.time.LocalDateTime since);

    /** 同一个问题累计发生多少次：修哪个最划算看这个数 */
    int countBySignature(@Param("signature") String signature);

    /** 人工复核（认可或改判都算看过），改判率的分母靠它才有意义 */
    int markReviewed(@Param("id") String id,
                     @Param("reviewedAt") java.time.LocalDateTime reviewedAt,
                     @Param("reviewedBy") String reviewedBy);

    /** 候选定位已写回元素行，打上时间戳避免重复采纳 */
    int markApplied(@Param("id") String id, @Param("appliedAt") java.time.LocalDateTime appliedAt);

    int deleteById(@Param("id") String id);
}
