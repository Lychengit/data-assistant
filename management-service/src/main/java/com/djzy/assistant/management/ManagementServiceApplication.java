package com.djzy.assistant.management;

import com.djzy.assistant.management.config.ManagementProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 管理后台（§18.4.6）：M1 登录与账号、M2 角色/权限配置、M4 接口注册、M5 口径字典。
 *
 * <p>平台元数据出口**直连 PG**（不经网关、不套用户范围过滤），因为角色/接口/口径这些对象
 * 不挂在业务对象树上、没有 scope 可算（§18.4.6 两个数据出口）；判定函数仍复用 {@code common} 唯一实现。
 */
@SpringBootApplication
@EnableConfigurationProperties(ManagementProperties.class)
public class ManagementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ManagementServiceApplication.class, args);
    }
}
