/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：在 Servlet 请求进入控制器前处理请求头身份解析与 SecurityContext 建立。
 * 业务链路：核心入口/契约为 「doFilterInternal」；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「HeaderAuthenticationFilter」。
// 类型职责：在 Servlet 请求进入控制器前处理请求头身份解析与 SecurityContext 建立；本类型显式提供 「doFilterInternal」、「isValidActorId」、「authenticate」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public final class HeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String ROLE_HEADER = "X-Role";
    public static final String SERVICE_IDENTITY_HEADER = "X-Service-Identity";

    private static final Pattern SAFE_ACTOR_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「HeaderAuthenticationFilter.doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain)」。
    // 具体功能：「HeaderAuthenticationFilter.doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain)」：执行do过滤器内部：先把已认证操作者放入当前请求安全上下文；实际协作者为 「SecurityContextHolder.clearContext」、「request.getHeader」、「filterChain.doFilter」、「isValidActorId」，最终返回「void」。
    // 上游调用：「HeaderAuthenticationFilter.doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain)」由使用「HeaderAuthenticationFilter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HeaderAuthenticationFilter.doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain)」向下依次触达 「SecurityContextHolder.clearContext」、「request.getHeader」、「filterChain.doFilter」、「isValidActorId」。
    // 系统意义：「HeaderAuthenticationFilter.doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain)」负责主链路中的“do过滤器内部”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String actorId = request.getHeader(USER_ID_HEADER);
        String roleValue = request.getHeader(ROLE_HEADER);
        String serviceIdentity = request.getHeader(SERVICE_IDENTITY_HEADER);

        if (isValidActorId(actorId)
                && roleValue != null
                && !ActorRole.SYSTEM.name().equals(roleValue)) {
            authenticate(actorId, roleValue);
        } else if (isValidActorId(serviceIdentity)) {
            authenticate(serviceIdentity, ActorRole.SYSTEM.name());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「HeaderAuthenticationFilter.isValidActorId(String)」。
    // 具体功能：「HeaderAuthenticationFilter.isValidActorId(String)」：判断是否Valid操作者标识；实际协作者为 「SAFE_ACTOR_ID.matcher」、「SAFE_ACTOR_ID.matcher(actorId).matches」，最终返回「boolean」。
    // 上游调用：「HeaderAuthenticationFilter.isValidActorId(String)」的上游调用点包括 「HeaderAuthenticationFilter.doFilterInternal」。
    // 下游影响：「HeaderAuthenticationFilter.isValidActorId(String)」向下依次触达 「SAFE_ACTOR_ID.matcher」、「SAFE_ACTOR_ID.matcher(actorId).matches」；计算结果以「boolean」交给调用方。
    // 系统意义：「HeaderAuthenticationFilter.isValidActorId(String)」负责主链路中的“Valid操作者标识”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    private static boolean isValidActorId(String actorId) {
        return actorId != null && SAFE_ACTOR_ID.matcher(actorId).matches();
    }

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「HeaderAuthenticationFilter.authenticate(String,String)」。
    // 具体功能：「HeaderAuthenticationFilter.authenticate(String,String)」：建立认证请求头身份解析与 SecurityContext 建立：先把已认证操作者放入当前请求安全上下文；实际协作者为 「UsernamePasswordAuthenticationToken.authenticated」、「SecurityContextHolder.getContext」、「SecurityContextHolder.clearContext」、「SecurityContextHolder.getContext().setAuthentication」；处理的关键状态/协议值包括 「ROLE_」，最终返回「void」。
    // 上游调用：「HeaderAuthenticationFilter.authenticate(String,String)」的上游调用点包括 「HeaderAuthenticationFilter.doFilterInternal」。
    // 下游影响：「HeaderAuthenticationFilter.authenticate(String,String)」向下依次触达 「UsernamePasswordAuthenticationToken.authenticated」、「SecurityContextHolder.getContext」、「SecurityContextHolder.clearContext」、「SecurityContextHolder.getContext().setAuthentication」。
    // 系统意义：「HeaderAuthenticationFilter.authenticate(String,String)」负责主链路中的“请求头身份解析与 SecurityContext 建立”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    private static void authenticate(String actorId, String roleValue) {
        try {
            ActorRole role = ActorRole.valueOf(roleValue);
            AuthenticatedActor actor = new AuthenticatedActor(actorId, role);
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            actor,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (IllegalArgumentException ignored) {
            SecurityContextHolder.clearContext();
        }
    }
}
