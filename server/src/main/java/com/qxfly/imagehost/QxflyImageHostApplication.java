package com.qxfly.imagehost;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.Environment;

/**
 * qxfly-image-host 图床管理 - 后端启动类。
 *
 * @author qxfly-image-host
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.qxfly.imagehost.config")
@MapperScan("com.qxfly.imagehost.mapper")
public class QxflyImageHostApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(QxflyImageHostApplication.class, args).getEnvironment();
        String port = env.getProperty("server.port", "8080");
        String ctx = env.getProperty("server.servlet.context-path", "");
        System.out.printf("""
                
                ----------------------------------------------------------
                  qxfly-image-host 服务已启动
                  接口地址 : http://127.0.0.1:%s%s
                  健康检查 : http://127.0.0.1:%s%s/actuator/health
                  当前环境 : %s
                ----------------------------------------------------------
                %n""", port, ctx, port, ctx, String.join(",", env.getActiveProfiles()));
    }
}
