package com.gcoll.dbcompare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录认证配置（默认开启；密码请在生产环境修改）。
 */
@ConfigurationProperties(prefix = "db-compare.security")
public class AuthProperties {

    /** 是否启用登录拦截（关闭后任意访问，仅建议本地调试） */
    private boolean enabled = true;
    private String username = "admin";
    /**
     * 登录密码（明文配置，启动时 BCrypt 编码）。
     * 默认已满足：长度≥12、大小写、数字、特殊字符。
     */
    private String password = "DbC0mpare#2026!Xk9$mQ7";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
