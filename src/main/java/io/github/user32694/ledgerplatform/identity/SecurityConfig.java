package io.github.user32694.ledgerplatform.identity;

import io.github.user32694.ledgerplatform.identity.internal.AdminUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
    private final ReadApiKeyAuthenticationFilter readApiKeyAuthenticationFilter;

    public SecurityConfig(ReadApiKeyAuthenticationFilter readApiKeyAuthenticationFilter) {
        this.readApiKeyAuthenticationFilter = readApiKeyAuthenticationFilter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 管理页面统一要求 ADMIN；登录、健康检查、OpenAPI 文档和静态资源保持匿名可访问。
        return http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/login",
                                "/css/**",
                                "/webjars/**",
                                "/actuator/health",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/api/v1/reconciliation/**")
                        .hasAnyRole("ADMIN", "READ_API")
                        .anyRequest()
                        .authenticated())
                .formLogin(login -> login
                        .loginPage("/login")
                        .defaultSuccessUrl("/admin", true)
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                // 浏览器页面未登录时可以跳转登录页；机器调用 API 时应得到明确的 401。
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new AntPathRequestMatcher("/api/**")))
                .addFilterBefore(readApiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AdminUserRepository repository) {
        return username -> repository.findByUsername(username)
                .map(admin -> User.withUsername(admin.username())
                        .password(admin.passwordHash())
                        .roles("ADMIN")
                        .disabled(!admin.enabled())
                        .build())
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException(
                        "Administrator not found"));
    }
}
