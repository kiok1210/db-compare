package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * API 控制器
 * 提供前端需要的枚举数据等
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    
    /**
     * 获取所有支持的数据库类型
     */
    @GetMapping("/database-types")
    public Map<String, Object> getDatabaseTypes() {
        Map<String, Object> result = new HashMap<>();
        DatabaseType[] types = DatabaseType.values();
        
        Map<String, String> typeMap = new HashMap<>();
        for (DatabaseType type : types) {
            typeMap.put(type.name(), type.getDisplayName());
        }
        
        result.put("success", true);
        result.put("data", typeMap);
        return result;
    }
    
    /**
     * 获取数据库类型的默认端口
     */
    @GetMapping("/database-default-port")
    public Map<String, Object> getDefaultPort(String dbType) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            DatabaseType type = DatabaseType.fromString(dbType);
            int defaultPort = getDefaultPortByType(type);
            
            result.put("success", true);
            result.put("data", defaultPort);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 根据数据库类型获取默认端口
     */
    private int getDefaultPortByType(DatabaseType dbType) {
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                return 3306;
            case POSTGRESQL:
                return 5432;
            case ORACLE:
                return 1521;
            case SQLSERVER:
                return 1433;
            case H2:
            case SQLITE:
                return 0; // 文件数据库无端口
            default:
                return 3306;
        }
    }
}
