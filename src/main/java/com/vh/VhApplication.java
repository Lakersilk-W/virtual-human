package com.vh;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@MapperScan("com.vh.repository.mapper")
@ConfigurationPropertiesScan("com.vh.config")
public class VhApplication {

    public static void main(String[] args) {
        SpringApplication.run(VhApplication.class, args);
    }
}
