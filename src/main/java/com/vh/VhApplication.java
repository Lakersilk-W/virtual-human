package com.vh;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.vh.repository.mapper")
@ConfigurationPropertiesScan("com.vh.config")
@EnableScheduling  // W3 修补: EpisodeFinalizationScheduler 需要
public class VhApplication {

    public static void main(String[] args) {
        SpringApplication.run(VhApplication.class, args);
    }
}
