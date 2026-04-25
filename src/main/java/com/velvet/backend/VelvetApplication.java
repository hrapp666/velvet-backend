package com.velvet.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Velvet · Touch what was touched.
 * <p>
 * 私藏，流转 — 通用 C2C 二手电商平台。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class VelvetApplication {
    public static void main(String[] args) {
        SpringApplication.run(VelvetApplication.class, args);
    }
}
