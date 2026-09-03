/*
 * 所属模块：案件受理兼容链路。
 * 文件职责：在 Spring 启动期装配Agent所需 Bean 和基础设施参数。
 * 业务链路：核心入口/契约为 「agentRestClient」、「http1RequestFactory」；承接旧版创建案件接口并调用接待 Agent 形成初步分析。
 * 关键边界：接待分析只是非最终建议，不能越权决定赔付或执行动作
 */
package com.example.dispute.caseintake.infrastructure;

import com.example.dispute.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// 所属模块：【案件受理兼容链路 / 外部集成层】类型「AgentClientConfiguration」。
// 类型职责：在 Spring 启动期装配Agent所需 Bean 和基础设施参数；本类型显式提供 「agentRestClient」、「http1RequestFactory」。
// 协作关系：主要由 「AgentClientConfigurationTest.forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest」 使用。
// 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Configuration
public class AgentClientConfiguration {

    // 所属模块：【案件受理兼容链路 / 外部集成层】「AgentClientConfiguration.agentRestClient(AppProperties)」。
    // 具体功能：「AgentClientConfiguration.agentRestClient(AppProperties)」：构建AgentRest客户端；实际协作者为 「Duration.ofMillis」、「properties.agent」、「agent.timeoutMs」、「agent.baseUrl」；处理的关键状态/协议值包括 「X-Service-Secret」，最终返回「RestClient」。
    // 上游调用：「AgentClientConfiguration.agentRestClient(AppProperties)」由 Spring ApplicationContext 启动过程调用，配置属性完成绑定后执行本工厂方法。
    // 下游影响：「AgentClientConfiguration.agentRestClient(AppProperties)」向下依次触达 「Duration.ofMillis」、「properties.agent」、「agent.timeoutMs」、「agent.baseUrl」；计算结果以「RestClient」交给调用方。
    // 系统意义：「AgentClientConfiguration.agentRestClient(AppProperties)」负责主链路中的“AgentRest客户端”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    @Bean("agentRestClient")
    RestClient agentRestClient(AppProperties properties) {
        AppProperties.Integration agent = properties.agent();
        Duration timeout = Duration.ofMillis(agent.timeoutMs());
        return RestClient.builder()
                .baseUrl(agent.baseUrl())
                .defaultHeader("X-Service-Secret", agent.serviceSecret())
                .requestFactory(http1RequestFactory(timeout))
                .build();
    }

    // 所属模块：【案件受理兼容链路 / 外部集成层】「AgentClientConfiguration.http1RequestFactory(Duration)」。
    // 具体功能：「AgentClientConfiguration.http1RequestFactory(Duration)」：构建http1请求工厂；实际协作者为 「HttpClient.newBuilder」、「requestFactory.setReadTimeout」、「connectTimeout」、「HttpClient.newBuilder().version」，最终返回「JdkClientHttpRequestFactory」。
    // 上游调用：「AgentClientConfiguration.http1RequestFactory(Duration)」的上游调用点包括 「AgentClientConfiguration.agentRestClient」、「AgentClientConfigurationTest.forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest」。
    // 下游影响：「AgentClientConfiguration.http1RequestFactory(Duration)」向下依次触达 「HttpClient.newBuilder」、「requestFactory.setReadTimeout」、「connectTimeout」、「HttpClient.newBuilder().version」；计算结果以「JdkClientHttpRequestFactory」交给调用方。
    // 系统意义：「AgentClientConfiguration.http1RequestFactory(Duration)」负责主链路中的“http1请求工厂”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    static JdkClientHttpRequestFactory http1RequestFactory(Duration timeout) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(timeout)
                                .build());
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }
}
