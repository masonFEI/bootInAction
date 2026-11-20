/**
 * LY.com Inc.
 * Copyright (c) 2004-2026 All Rights Reserved.
 */
package com.fxy.boot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * JdbcTemplateTest
 *
 * @author feixuanyu
 * @version 1.0.0
 * @since 2026-11-20 15:57
 */
@SpringBootTest
public class JdbcTemplateTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void testConnection() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertNotNull(result);
        assertEquals(1, result);
        System.out.println(">>> [JdbcTemplateTest] 连接成功，SELECT 1 返回: " + result);
    }

    @Test
    public void testQueryRegionTable() {
        List<Map<String, Object>> regions = jdbcTemplate.queryForList("SELECT * FROM t_region LIMIT 5");
        assertNotNull(regions);
        System.out.println(">>> [JdbcTemplateTest] t_region 前 5 行: " + regions);
    }
}
