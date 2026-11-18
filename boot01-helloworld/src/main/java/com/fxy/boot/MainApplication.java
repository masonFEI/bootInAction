/**
 * LY.com Inc.
 * Copyright (c) 2004-2026 All Rights Reserved.
 */
package com.fxy.boot;

import ch.qos.logback.core.db.DBHelper;
import com.fxy.boot.bean.User;
import com.fxy.boot.config.MyConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

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

        // 3.从容器中获取组件

        User user01 = run.getBean("user01", User.class);
        User user02 = run.getBean("user01", User.class);

        System.out.println(user01 == user02);

        // 配置类也是个组件
        MyConfig myConfig = run.getBean("myConfig", MyConfig.class);
        System.out.println(myConfig);

        // 如果 @Configuration(proxyBeanMethods= true) 代理对象调用方法，springBoot总会检查这个组件是否在容器中有，如果有，则不会新创
        // 保证组件的单实例
        User user03 = myConfig.user01();
        User user04 = myConfig.user01();
        System.out.println(user03 == user04);


        System.out.println("=========================================");
        String[] beanNamesForType = run.getBeanNamesForType(User.class);
        for (String s : beanNamesForType) {
            System.out.println(s);
        }

        DBHelper bean = run.getBean(DBHelper.class);
        System.out.println(bean);


    }

}
