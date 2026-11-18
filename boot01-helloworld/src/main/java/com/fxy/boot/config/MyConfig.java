/**
 * LY.com Inc.
 * Copyright (c) 2004-2026 All Rights Reserved.
 */
package com.fxy.boot.config;

import ch.qos.logback.core.db.DBHelper;
import com.fxy.boot.bean.User;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 告诉springBoot这是一个配置类
 * <p>
 * proxyBeanMethods默认为true.代理bean的方法
 * <p>
 * springBoot的两种配置模式：
 * Full(全量，proxyBeanMethods=true.容器中会保留代理对象)
 * Lite（轻量，proxyBeanMethods=false.容器中不会保留代理对象，启动会比较快，因为跳过了检查容器中是否存在该组件）
 *
 * 解决组件依赖的问题
 *
 * @author feixuanyu
 * @version 1.0.0
 * @since 2026-11-18 16:17
 */
@Configuration
//属性proxyBeanMethods默认为true.代理bean的方法

@Import({User.class, DBHelper.class})
public class MyConfig {


    @Bean//给容器中添加组件，方法名为组件的id，返回类型就是组件类型，返回值就是组件在容器中的实例
    public User user01() {
        return new User("张三", 18);
    }
}
