/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：在 Spring 启动期装配证据Integration所需 Bean 和基础设施参数。
 * 业务链路：核心入口/契约为 「ocrRestClient」、「evidenceSearchRestClient」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure;

import com.example.dispute.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// 所属模块：【证据与版本化卷宗 / 外部集成层】类型「EvidenceIntegrationConfiguration」。
// 类型职责：在 Spring 启动期装配证据Integration所需 Bean 和基础设施参数；本类型显式提供 「ocrRestClient」、「evidenceSearchRestClient」、「client」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Configuration
public class EvidenceIntegrationConfiguration {

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「EvidenceIntegrationConfiguration.ocrRestClient(AppProperties)」。
    // 具体功能：「EvidenceIntegrationConfiguration.ocrRestClient(AppProperties)」：构建OCRRest客户端；实际协作者为 「properties.ocr」、「ocr.baseUrl」、「ocr.serviceSecret」、「ocr.timeoutMs」，最终返回「RestClient」。
    // 上游调用：「EvidenceIntegrationConfiguration.ocrRestClient(AppProperties)」由 Spring ApplicationContext 启动过程调用，配置属性完成绑定后执行本工厂方法。
    // 下游影响：「EvidenceIntegrationConfiguration.ocrRestClient(AppProperties)」向下依次触达 「properties.ocr」、「ocr.baseUrl」、「ocr.serviceSecret」、「ocr.timeoutMs」；计算结果以「RestClient」交给调用方。
    // 系统意义：「EvidenceIntegrationConfiguration.ocrRestClient(AppProperties)」负责主链路中的“OCRRest客户端”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    @Bean("ocrRestClient")
    RestClient ocrRestClient(AppProperties properties) {
        AppProperties.Integration ocr = properties.ocr();
        return client(ocr.baseUrl(), ocr.serviceSecret(), ocr.timeoutMs());
    }

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「EvidenceIntegrationConfiguration.evidenceSearchRestClient(AppProperties)」。
    // 具体功能：「EvidenceIntegrationConfiguration.evidenceSearchRestClient(AppProperties)」：构建证据检索Rest客户端；实际协作者为 「properties.elasticsearch」、「client」、「properties.elasticsearch().url」，最终返回「RestClient」。
    // 上游调用：「EvidenceIntegrationConfiguration.evidenceSearchRestClient(AppProperties)」由 Spring ApplicationContext 启动过程调用，配置属性完成绑定后执行本工厂方法。
    // 下游影响：「EvidenceIntegrationConfiguration.evidenceSearchRestClient(AppProperties)」向下依次触达 「properties.elasticsearch」、「client」、「properties.elasticsearch().url」；计算结果以「RestClient」交给调用方。
    // 系统意义：「EvidenceIntegrationConfiguration.evidenceSearchRestClient(AppProperties)」负责主链路中的“证据检索Rest客户端”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    @Bean("evidenceSearchRestClient")
    RestClient evidenceSearchRestClient(AppProperties properties) {
        return client(properties.elasticsearch().url(), null, 5000);
    }

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「EvidenceIntegrationConfiguration.client(String,String,int)」。
    // 具体功能：「EvidenceIntegrationConfiguration.client(String,String,int)」：构建客户端；实际协作者为 「HttpClient.newBuilder」、「factory.setReadTimeout」、「Duration.ofMillis」、「builder.defaultHeader」；处理的关键状态/协议值包括 「X-Service-Secret」，最终返回「RestClient」。
    // 上游调用：「EvidenceIntegrationConfiguration.client(String,String,int)」的上游调用点包括 「EvidenceIntegrationConfiguration.ocrRestClient」、「EvidenceIntegrationConfiguration.evidenceSearchRestClient」。
    // 下游影响：「EvidenceIntegrationConfiguration.client(String,String,int)」向下依次触达 「HttpClient.newBuilder」、「factory.setReadTimeout」、「Duration.ofMillis」、「builder.defaultHeader」；计算结果以「RestClient」交给调用方。
    // 系统意义：「EvidenceIntegrationConfiguration.client(String,String,int)」负责主链路中的“客户端”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static RestClient client(String baseUrl, String serviceSecret, int timeoutMs) {
        Duration timeout = Duration.ofMillis(timeoutMs);
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(timeout)
                                .build());
        factory.setReadTimeout(timeout);
        RestClient.Builder builder =
                RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (serviceSecret != null) {
            builder.defaultHeader("X-Service-Secret", serviceSecret);
        }
        return builder.build();
    }
}
