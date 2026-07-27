package com.gcoll.dbcompare.service;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConnectionTestResult {
    private boolean success;
    private String message;

    public static ConnectionTestResult ok() {
        return new ConnectionTestResult(true, "连接成功");
    }

    public static ConnectionTestResult fail(String message) {
        return new ConnectionTestResult(false, message);
    }
}
