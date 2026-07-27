package com.gcoll.dbcompare.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源：比对报告 HTML 文件
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 同步报告等静态文件；比对 report.html 由 CompareReportFileController 增强后输出
        registry.addResourceHandler("/reports/sync/**")
            .addResourceLocations("file:reports/sync/");
        registry.addResourceHandler("/reports/**/*.css", "/reports/**/*.js", "/reports/**/*.png",
                        "/reports/**/*.jpg", "/reports/**/*.gif", "/reports/**/*.json")
            .addResourceLocations("file:reports/");
    }
}
