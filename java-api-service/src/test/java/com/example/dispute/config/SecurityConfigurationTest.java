/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：验证安全，覆盖 「anonymousRequestReceivesUnifiedUnauthorizedResponse」、「validActorHeadersAuthenticateKnownRole」、「actorHeadersCannotCallInternalServiceRoutes」、「serviceIdentityCanCallInternalServiceRoutes」、「partyRolesCannotEnterReviewApis」、「unknownRoleCannotAuthenticate」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.common.trace.TraceIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【身份鉴权与运行配置 / 自动化测试层】类型「SecurityConfigurationTest」。
// 类型职责：集中验证安全的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「anonymousRequestReceivesUnifiedUnauthorizedResponse」、「validActorHeadersAuthenticateKnownRole」、「actorHeadersCannotCallInternalServiceRoutes」、「serviceIdentityCanCallInternalServiceRoutes」、「partyRolesCannotEnterReviewApis」、「unknownRoleCannotAuthenticate」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.flyway.enabled=false",
            "spring.data.redis.repositories.enabled=false",
            "management.health.redis.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "dispute.scheduling.enabled=false"
        })
@Import(SecurityConfigurationTest.TestEndpointConfiguration.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class SecurityConfigurationTest {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private Filter springSecurityFilterChain;
    @Autowired private ApplicationContext applicationContext;

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「SecurityConfigurationTest.anonymousRequestReceivesUnifiedUnauthorizedResponse()」。
    // 具体功能：「SecurityConfigurationTest.anonymousRequestReceivesUnifiedUnauthorizedResponse()」：复现“核对完整业务行为（场景方法「anonymousRequestReceivesUnifiedUnauthorizedResponse」）”场景：驱动 「restTemplate.getForEntity」、「response.getStatusCode」、「response.getBody」、「response.getHeaders」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「success」、「code」、「UNAUTHORIZED」、「request_id」。
    // 上游调用：「SecurityConfigurationTest.anonymousRequestReceivesUnifiedUnauthorizedResponse()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SecurityConfigurationTest.anonymousRequestReceivesUnifiedUnauthorizedResponse()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SecurityConfigurationTest.anonymousRequestReceivesUnifiedUnauthorizedResponse()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「success」、「code」、「UNAUTHORIZED」、「request_id」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void anonymousRequestReceivesUnifiedUnauthorizedResponse() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity(url("/api/disputes/security-test"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
                .containsEntry("success", false)
                .containsEntry("code", "UNAUTHORIZED");
        assertThat(response.getBody().get("request_id").toString()).startsWith("REQ_");
        assertThat(response.getBody().get("trace_id").toString()).startsWith("TRACE_");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「SecurityConfigurationTest.validActorHeadersAuthenticateKnownRole()」。
    // 具体功能：「SecurityConfigurationTest.validActorHeadersAuthenticateKnownRole()」：复现“核对完整业务行为（场景方法「validActorHeadersAuthenticateKnownRole」）”场景：驱动 「restTemplate.exchange」、「response.getStatusCode」、「response.getBody」、「url」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「reviewer_001」、「PLATFORM_REVIEWER」、「ok」。
    // 上游调用：「SecurityConfigurationTest.validActorHeadersAuthenticateKnownRole()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SecurityConfigurationTest.validActorHeadersAuthenticateKnownRole()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SecurityConfigurationTest.validActorHeadersAuthenticateKnownRole()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「reviewer_001」、「PLATFORM_REVIEWER」、「ok」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void validActorHeadersAuthenticateKnownRole() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "reviewer_001");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "PLATFORM_REVIEWER");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        url("/api/reviews/security-test"),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("ok");
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「SecurityConfigurationTest.actorHeadersCannotCallInternalServiceRoutes()」。
    // 具体功能：「SecurityConfigurationTest.actorHeadersCannotCallInternalServiceRoutes()」：复现“核对完整业务行为（场景方法「actorHeadersCannotCallInternalServiceRoutes」）”场景：驱动 「restTemplate.exchange」、「response.getStatusCode」、「url」、「assertThat(response.getStatusCode()).isEqualTo」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「reviewer_001」、「PLATFORM_REVIEWER」。
    // 上游调用：「SecurityConfigurationTest.actorHeadersCannotCallInternalServiceRoutes()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SecurityConfigurationTest.actorHeadersCannotCallInternalServiceRoutes()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SecurityConfigurationTest.actorHeadersCannotCallInternalServiceRoutes()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「reviewer_001」、「PLATFORM_REVIEWER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void actorHeadersCannotCallInternalServiceRoutes() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "reviewer_001");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "PLATFORM_REVIEWER");

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        url("/internal/security-test"),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「SecurityConfigurationTest.serviceIdentityCanCallInternalServiceRoutes()」。
    // 具体功能：「SecurityConfigurationTest.serviceIdentityCanCallInternalServiceRoutes()」：复现“核对完整业务行为（场景方法「serviceIdentityCanCallInternalServiceRoutes」）”场景：驱动 「restTemplate.exchange」、「response.getStatusCode」、「url」、「assertThat(response.getStatusCode()).isEqualTo」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「trusted-platform-adapter」。
    // 上游调用：「SecurityConfigurationTest.serviceIdentityCanCallInternalServiceRoutes()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SecurityConfigurationTest.serviceIdentityCanCallInternalServiceRoutes()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SecurityConfigurationTest.serviceIdentityCanCallInternalServiceRoutes()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「trusted-platform-adapter」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void serviceIdentityCanCallInternalServiceRoutes() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(
                HeaderAuthenticationFilter.SERVICE_IDENTITY_HEADER,
                "trusted-platform-adapter");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        url("/internal/security-test"),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「SecurityConfigurationTest.partyRolesCannotEnterReviewApis()」。
    // 具体功能：「SecurityConfigurationTest.partyRolesCannotEnterReviewApis()」：复现“核对完整业务行为（场景方法「partyRolesCannotEnterReviewApis」）”场景：驱动 「restTemplate.exchange」、「response.getStatusCode」、「url」、「assertThat(response.getStatusCode()).isEqualTo」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user_001」、「USER」。
    // 上游调用：「SecurityConfigurationTest.partyRolesCannotEnterReviewApis()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SecurityConfigurationTest.partyRolesCannotEnterReviewApis()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SecurityConfigurationTest.partyRolesCannotEnterReviewApis()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「user_001」、「USER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void partyRolesCannotEnterReviewApis() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "user_001");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "USER");

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        url("/api/reviews/security-test"),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「SecurityConfigurationTest.unknownRoleCannotAuthenticate()」。
    // 具体功能：「SecurityConfigurationTest.unknownRoleCannotAuthenticate()」：复现“核对完整业务行为（场景方法「unknownRoleCannotAuthenticate」）”场景：驱动 「restTemplate.exchange」、「response.getStatusCode」、「response.getBody」、「url」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「attacker」、「SUPERUSER」、「code」、「UNAUTHORIZED」。
    // 上游调用：「SecurityConfigurationTest.unknownRoleCannotAuthenticate()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SecurityConfigurationTest.unknownRoleCannotAuthenticate()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SecurityConfigurationTest.unknownRoleCannotAuthenticate()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「attacker」、「SUPERUSER」、「code」、「UNAUTHORIZED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void unknownRoleCannotAuthenticate() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "attacker");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "SUPERUSER");

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        url("/api/disputes/security-test"),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "UNAUTHORIZED");
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「SecurityConfigurationTest.asyncSseDispatchBypassesAuthenticationGateAfterResponseHasStarted()」。
    // 具体功能：「SecurityConfigurationTest.asyncSseDispatchBypassesAuthenticationGateAfterResponseHasStarted()」：复现“核对完整业务行为（场景方法「asyncSseDispatchBypassesAuthenticationGateAfterResponseHasStarted」）”场景：驱动 「request.setDispatcherType」、「springSecurityFilterChain.doFilter」、「response.getStatus」、「assertThat(reachedApplication).isTrue」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「GET」。
    // 上游调用：「SecurityConfigurationTest.asyncSseDispatchBypassesAuthenticationGateAfterResponseHasStarted()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SecurityConfigurationTest.asyncSseDispatchBypassesAuthenticationGateAfterResponseHasStarted()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SecurityConfigurationTest.asyncSseDispatchBypassesAuthenticationGateAfterResponseHasStarted()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「GET」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void asyncSseDispatchBypassesAuthenticationGateAfterResponseHasStarted()
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "GET", "/api/disputes/CASE_security_test/events");
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedApplication = new AtomicBoolean(false);
        FilterChain terminalChain =
                (servletRequest, servletResponse) ->
                        reachedApplication.set(true);

        springSecurityFilterChain.doFilter(request, response, terminalChain);

        assertThat(reachedApplication).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「SecurityConfigurationTest.schedulingCanBeDisabledForFocusedSecurityContexts()」。
    // 具体功能：「SecurityConfigurationTest.schedulingCanBeDisabledForFocusedSecurityContexts()」：复现“核对完整业务行为（场景方法「schedulingCanBeDisabledForFocusedSecurityContexts」）”场景：驱动 「applicationContext.getBeansOfType」，再用 「assertThat」 核对返回值、状态变化或协作者调用。
    // 上游调用：「SecurityConfigurationTest.schedulingCanBeDisabledForFocusedSecurityContexts()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SecurityConfigurationTest.schedulingCanBeDisabledForFocusedSecurityContexts()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SecurityConfigurationTest.schedulingCanBeDisabledForFocusedSecurityContexts()」守住「身份鉴权与运行配置」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void schedulingCanBeDisabledForFocusedSecurityContexts() {
        assertThat(
                        applicationContext.getBeansOfType(
                                ScheduledAnnotationBeanPostProcessor.class))
                .isEmpty();
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「SecurityConfigurationTest.url(String)」。
    // 具体功能：「SecurityConfigurationTest.url(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「url」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
    // 上游调用：「SecurityConfigurationTest.url(String)」由本测试类中的 「SecurityConfigurationTest.anonymousRequestReceivesUnifiedUnauthorizedResponse」、「SecurityConfigurationTest.validActorHeadersAuthenticateKnownRole」、「SecurityConfigurationTest.actorHeadersCannotCallInternalServiceRoutes」、「SecurityConfigurationTest.serviceIdentityCanCallInternalServiceRoutes」 调用。
    // 下游影响：「SecurityConfigurationTest.url(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「SecurityConfigurationTest.url(String)」守住「身份鉴权与运行配置」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】类型「TestEndpointConfiguration」。
    // 类型职责：在 Spring 启动期装配Endpoint所需 Bean 和基础设施参数；本类型显式提供 「securityTestController」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration(proxyBeanMethods = false)
    static class TestEndpointConfiguration {

        // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「SecurityConfigurationTest.TestEndpointConfiguration.securityTestController()」。
        // 具体功能：「SecurityConfigurationTest.TestEndpointConfiguration.securityTestController()」：作为测试辅助方法为“核对完整业务行为（场景方法「securityTestController」）”组装或读取「SecurityTestController」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「SecurityConfigurationTest.TestEndpointConfiguration.securityTestController()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「SecurityConfigurationTest.TestEndpointConfiguration.securityTestController()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「SecurityConfigurationTest.TestEndpointConfiguration.securityTestController()」守住「身份鉴权与运行配置」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        SecurityTestController securityTestController() {
            return new SecurityTestController();
        }
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】类型「SecurityTestController」。
    // 类型职责：把安全能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「protectedEndpoint」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @RestController
    static class SecurityTestController {

        // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「SecurityConfigurationTest.SecurityTestController.protectedEndpoint()」。
        // 具体功能：「SecurityConfigurationTest.SecurityTestController.protectedEndpoint()」：作为测试辅助方法为“核对完整业务行为（场景方法「protectedEndpoint」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
        // 上游调用：「SecurityConfigurationTest.SecurityTestController.protectedEndpoint()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「SecurityConfigurationTest.SecurityTestController.protectedEndpoint()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「SecurityConfigurationTest.SecurityTestController.protectedEndpoint()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「ok」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        @GetMapping({
            "/api/disputes/security-test",
            "/api/reviews/security-test",
            "/internal/security-test"
        })
        String protectedEndpoint() {
            return "ok";
        }
    }
}
