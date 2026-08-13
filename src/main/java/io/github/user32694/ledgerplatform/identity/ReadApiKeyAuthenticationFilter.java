package io.github.user32694.ledgerplatform.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 只读集成 API 的轻量认证：用环境变量中的密钥换取 READ_API 角色。 */
@Component
class ReadApiKeyAuthenticationFilter extends OncePerRequestFilter {
    private static final String HEADER = "X-Read-Api-Key";
    private final String configuredKey;

    ReadApiKeyAuthenticationFilter(@Value("${app.read-api-key:}") String configuredKey) {
        this.configuredKey = configuredKey == null ? "" : configuredKey.strip();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/reconciliation/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String suppliedKey = request.getHeader(HEADER);
        if (!configuredKey.isBlank()
                && suppliedKey != null
                && java.security.MessageDigest.isEqual(
                        configuredKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        suppliedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "read-api-client",
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_READ_API")));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }
}
