package com.gcoll.dbcompare.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * 全站登录拦截：未登录不可访问任何业务资源（含 API / 报告静态页）。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final AuthProperties authProperties;
    private final PasswordEncoder passwordEncoder;

    public SecurityConfig(AuthProperties authProperties, PasswordEncoder passwordEncoder) {
        this.authProperties = authProperties;
        this.passwordEncoder = passwordEncoder;
    }

    @Bean
    public static PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        String user = authProperties.getUsername() != null ? authProperties.getUsername().trim() : "admin";
        String raw = authProperties.getPassword() != null ? authProperties.getPassword() : "";
        if (user.isEmpty()) {
            user = "admin";
        }
        if (raw.isEmpty()) {
            throw new IllegalStateException("db-compare.security.password 不能为空");
        }
        if (!AuthPasswordRules.isComplex(raw)) {
            log.warn("当前登录密码未达到复杂度要求（≥12 且含大小写/数字/特殊字符），请尽快修改 application.yml");
        }
        auth.inMemoryAuthentication()
                .passwordEncoder(passwordEncoder)
                .withUser(user)
                .password(passwordEncoder.encode(raw))
                .roles("ADMIN");
        if (authProperties.isEnabled()) {
            log.info("登录认证已启用，用户名={}（密码见 application.yml → db-compare.security）", user);
        } else {
            log.warn("登录认证已关闭（db-compare.security.enabled=false），任意访问开放 — 勿用于生产");
        }
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        // 内部工具大量 JSON POST；会话 Cookie 已足够，关闭 CSRF 避免拖垮现有 Ajax
        http.csrf().disable();

        if (!authProperties.isEnabled()) {
            http.authorizeRequests().anyRequest().permitAll();
            return;
        }

        http.authorizeRequests()
                .antMatchers(
                        "/login",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/webjars/**",
                        "/favicon.ico",
                        "/error"
                ).permitAll()
                .anyRequest().authenticated()
                .and()
                .formLogin()
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error")
                .permitAll()
                .and()
                .logout()
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .permitAll()
                .and()
                .headers()
                .frameOptions().sameOrigin();
    }
}
