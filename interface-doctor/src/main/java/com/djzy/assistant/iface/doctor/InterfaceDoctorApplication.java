package com.djzy.assistant.iface.doctor;

import com.djzy.assistant.iface.doctor.config.DoctorInterfaceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 医生域接口服务：真正接触业务数据的服务（§18.4.5）。
 *
 * <p>信任边界（§20.1）：只接受来自网关的连接且必须验签；验签通过后**直接使用**网关下发的 userId，
 * 不重解析身份、不重新判定、不查权限库、不回查网关。
 *
 * <p>数据可见范围不在这里配置、也不由网关下发：范围管理落地后由各接口基于登录人自行推导——
 * 范围必须与业务表结构一起理解（哪个字段代表科室、哪个代表本人），而那是接口服务独有的知识。
 *
 * <p>启动时会与 {@code sys_api} 对账（接口是否都登记、路径是否都在验签保护范围内、入参 DTO 是否与
 * {@code param_schema} 一致），不一致即拒绝启动。
 */
@SpringBootApplication
@EnableConfigurationProperties(DoctorInterfaceProperties.class)
public class InterfaceDoctorApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterfaceDoctorApplication.class, args);
    }
}