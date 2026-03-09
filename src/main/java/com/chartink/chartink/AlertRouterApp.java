package com.chartink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // Required for the midnight cleanup task
public class AlertRouterApp {
    public static void main(String[] args) {
        SpringApplication.run(AlertRouterApp.class, args);
    }
}