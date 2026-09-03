/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：承载Minio证据存储在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「storeOriginal」、「loadOriginal」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ExternalServiceException;
import com.example.dispute.config.AppProperties;
import com.example.dispute.evidence.application.EvidenceStorage;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.springframework.stereotype.Component;

// 所属模块：【证据与版本化卷宗 / 外部集成层】类型「MinioEvidenceStorage」。
// 类型职责：承载Minio证据存储在当前业务模块中的规则与协作边界；本类型显式提供 「MinioEvidenceStorage」、「storeOriginal」、「loadOriginal」。
// 协作关系：主要由 「MinioEvidenceStorageIntegrationTest.storesOriginalObjectInPrivateEvidenceBucket」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class MinioEvidenceStorage implements EvidenceStorage {

    private final MinioClient minioClient;
    private final String originalBucket;

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「MinioEvidenceStorage.MinioEvidenceStorage(MinioClient,AppProperties)」。
    // 具体功能：「MinioEvidenceStorage.MinioEvidenceStorage(MinioClient,AppProperties)」：通过构造器接收 「minioClient」(MinioClient)、「properties」(AppProperties) 并保存为「MinioEvidenceStorage」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「MinioEvidenceStorage.MinioEvidenceStorage(MinioClient,AppProperties)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「MinioEvidenceStorageIntegrationTest.storesOriginalObjectInPrivateEvidenceBucket」 显式创建。
    // 下游影响：「MinioEvidenceStorage.MinioEvidenceStorage(MinioClient,AppProperties)」向下依次触达 「properties.minio」、「properties.minio().evidenceOriginalBucket」。
    // 系统意义：「MinioEvidenceStorage.MinioEvidenceStorage(MinioClient,AppProperties)」负责主链路中的“Minio证据存储”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public MinioEvidenceStorage(MinioClient minioClient, AppProperties properties) {
        this.minioClient = minioClient;
        this.originalBucket = properties.minio().evidenceOriginalBucket();
    }

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「MinioEvidenceStorage.storeOriginal(String,String,String,String,byte[])」。
    // 具体功能：「MinioEvidenceStorage.storeOriginal(String,String,String,String,byte[])」：存储原件；实际协作者为 「minioClient.putObject」、「contentType」、「PutObjectArgs.builder().bucket(originalBucket).object」、「PutObjectArgs.builder().bucket」；不满足前置条件时抛出 「ExternalServiceException」；处理的关键状态/协议值包括 「case_id」、「evidence_id」，最终返回「StoredObject」。
    // 上游调用：「MinioEvidenceStorage.storeOriginal(String,String,String,String,byte[])」由使用「MinioEvidenceStorage」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「MinioEvidenceStorage.storeOriginal(String,String,String,String,byte[])」向下依次触达 「minioClient.putObject」、「contentType」、「PutObjectArgs.builder().bucket(originalBucket).object」、「PutObjectArgs.builder().bucket」；计算结果以「StoredObject」交给调用方。
    // 系统意义：「MinioEvidenceStorage.storeOriginal(String,String,String,String,byte[])」负责主链路中的“原件”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    @Override
    public StoredObject storeOriginal(
            String caseId,
            String evidenceId,
            String filename,
            String contentType,
            byte[] content) {
        String objectKey = caseId + "/" + evidenceId + "/" + filename;
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(originalBucket)
                            .object(objectKey)
                            .contentType(contentType)
                            .stream(input, content.length, -1)
                            .build());
            return new StoredObject(originalBucket, objectKey);
        } catch (Exception exception) {
            throw new ExternalServiceException(
                    ErrorCode.EVIDENCE_UPLOAD_FAILED,
                    "evidence object storage failed",
                    Map.of("case_id", caseId, "evidence_id", evidenceId));
        }
    }

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「MinioEvidenceStorage.loadOriginal(String,String)」。
    // 具体功能：「MinioEvidenceStorage.loadOriginal(String,String)」：加载原件；实际协作者为 「minioClient.getObject」、「input.readAllBytes」、「GetObjectArgs.builder().bucket(bucket).object」、「GetObjectArgs.builder().bucket」；不满足前置条件时抛出 「ExternalServiceException」；处理的关键状态/协议值包括 「bucket」、「object_key」，最终返回「byte[]」。
    // 上游调用：「MinioEvidenceStorage.loadOriginal(String,String)」由使用「MinioEvidenceStorage」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「MinioEvidenceStorage.loadOriginal(String,String)」向下依次触达 「minioClient.getObject」、「input.readAllBytes」、「GetObjectArgs.builder().bucket(bucket).object」、「GetObjectArgs.builder().bucket」；计算结果以「byte[]」交给调用方。
    // 系统意义：「MinioEvidenceStorage.loadOriginal(String,String)」负责主链路中的“原件”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    @Override
    public byte[] loadOriginal(String bucket, String objectKey) {
        try (var input =
                minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectKey)
                                .build())) {
            return input.readAllBytes();
        } catch (Exception exception) {
            throw new ExternalServiceException(
                    ErrorCode.EVIDENCE_NOT_FOUND,
                    "evidence object not found",
                    Map.of("bucket", bucket, "object_key", objectKey));
        }
    }
}
