/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：承载Rest证据检索索引在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「indexMetadata」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure;

import com.example.dispute.evidence.application.EvidenceSearchIndexer;
import com.example.dispute.evidence.application.EvidenceView;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// 所属模块：【证据与版本化卷宗 / 外部集成层】类型「RestClientEvidenceSearchIndexer」。
// 类型职责：承载Rest证据检索索引在当前业务模块中的规则与协作边界；本类型显式提供 「RestClientEvidenceSearchIndexer」、「indexMetadata」。
// 协作关系：主要由 「EvidenceSearchIndexerIntegrationTest.indexesEvidenceMetadataIntoSearchableEvidenceIndex」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class RestClientEvidenceSearchIndexer implements EvidenceSearchIndexer {

    private final RestClient restClient;

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「RestClientEvidenceSearchIndexer.RestClientEvidenceSearchIndexer(RestClient)」。
    // 具体功能：「RestClientEvidenceSearchIndexer.RestClientEvidenceSearchIndexer(RestClient)」：通过构造器接收 「restClient」(RestClient) 并保存为「RestClientEvidenceSearchIndexer」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RestClientEvidenceSearchIndexer.RestClientEvidenceSearchIndexer(RestClient)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「EvidenceSearchIndexerIntegrationTest.indexesEvidenceMetadataIntoSearchableEvidenceIndex」 显式创建。
    // 下游影响：「RestClientEvidenceSearchIndexer.RestClientEvidenceSearchIndexer(RestClient)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RestClientEvidenceSearchIndexer.RestClientEvidenceSearchIndexer(RestClient)」负责主链路中的“Rest客户端证据检索索引”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RestClientEvidenceSearchIndexer(
            @Qualifier("evidenceSearchRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「RestClientEvidenceSearchIndexer.indexMetadata(EvidenceView)」。
    // 具体功能：「RestClientEvidenceSearchIndexer.indexMetadata(EvidenceView)」：索引元数据：先调用受配置和超时控制的内部 HTTP 服务；实际协作者为 「evidence.id」、「evidence.caseId」、「evidence.evidenceType」、「evidence.sourceType」；处理的关键状态/协议值包括 「evidence_id」、「case_id」、「evidence_type」、「source_type」，最终返回「void」。
    // 上游调用：「RestClientEvidenceSearchIndexer.indexMetadata(EvidenceView)」由使用「RestClientEvidenceSearchIndexer」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RestClientEvidenceSearchIndexer.indexMetadata(EvidenceView)」向下依次触达 「evidence.id」、「evidence.caseId」、「evidence.evidenceType」、「evidence.sourceType」。
    // 系统意义：「RestClientEvidenceSearchIndexer.indexMetadata(EvidenceView)」负责主链路中的“元数据”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    @Override
    public void indexMetadata(EvidenceView evidence) {
        restClient
                .put()
                .uri("/evidence_index/_doc/{id}", evidence.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        Map.of(
                                "evidence_id", evidence.id(),
                                "case_id", evidence.caseId(),
                                "evidence_type", evidence.evidenceType(),
                                "source_type", evidence.sourceType(),
                                "file_hash", evidence.fileHash(),
                                "parse_status", evidence.parseStatus(),
                                "desensitized", evidence.desensitized()))
                .retrieve()
                .toBodilessEntity();
    }
}
