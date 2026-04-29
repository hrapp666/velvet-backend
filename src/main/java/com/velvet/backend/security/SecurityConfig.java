package com.velvet.backend.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Value("${velvet.cors.extra-origins:}")
    private String extraOrigins;

    /**
     * 是否允许 localhost CORS — 生产必须 false
     * 通过 VELVET_CORS_ALLOW_LOCALHOST=false 关闭
     */
    @Value("${velvet.cors.allow-localhost:false}")
    private boolean allowLocalhost;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/health", "/api/v1/health/deep").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/moments/public/**").permitAll()
                        .requestMatchers("/api/v1/comments/public/**").permitAll()
                        .requestMatchers("/api/v1/users/public/**").permitAll()
                        .requestMatchers("/api/v1/search/public/**").permitAll()
                        .requestMatchers("/api/v1/reviews/public/**").permitAll()
                        .requestMatchers("/api/v1/merchants/public/**").permitAll()
                        .requestMatchers("/api/v1/payments/notify/**").permitAll()
                        .requestMatchers("/api/v1/payments/config").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        // Actuator (audit P0 监控) — health/info/prometheus 公开 · 其余需 admin
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // 未携带或携带无效 JWT 的请求 → 返回 401（让前端走 refresh 流程）
                // 之前默认 403 + 空 body 导致 H5/Flutter 的 401-only 刷新逻辑永远不触发
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED))
                )
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        // 生产固定域名 · 临时 tunnel 通过 VELVET_CORS_EXTRA 环境变量配置
        // localhost 仅在 VELVET_CORS_ALLOW_LOCALHOST=true 时启用（开发环境）
        List<String> origins = new ArrayList<>(List.of(
                "https://*.velvet.app",
                "https://*.velvet.market",
                "https://hrapp666.github.io"
        ));
        if (allowLocalhost) {
            origins.add("http://localhost:*");
            origins.add("http://127.0.0.1:*");
        }
        if (extraOrigins != null && !extraOrigins.isBlank()) {
            for (String o : extraOrigins.split(",")) {
                if (!o.isBlank()) origins.add(o.trim());
            }
        }
        cfg.setAllowedOriginPatterns(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
