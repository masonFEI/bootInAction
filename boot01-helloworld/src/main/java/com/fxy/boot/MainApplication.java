/**
 * LY.com Inc.
 * Copyright (c) 2004-2026 All Rights Reserved.
 */
package com.fxy.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

/**
 * springBoot的主程序类
 *
 * @author feixuanyu
 * @version 1.0.0
 * @since 2026-11-18 13:53
 */

@SpringBootApplication
public class MainApplication {


    public static void main(String[] args) {
        // 1.返回IoC容器
        ConfigurableApplicationContext run = SpringApplication.run(MainApplication.class, args);

        // 2.查看容器里面的组件
        String[] names = run.getBeanDefinitionNames();
        for (String name : names) {
            System.out.println(name);
        }
    }

}
