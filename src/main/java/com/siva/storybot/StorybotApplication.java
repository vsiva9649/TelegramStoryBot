package com.siva.storybot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StorybotApplication {

    public static void main(String[] args) {
        SpringApplication.run(StorybotApplication.class, args);
    }

}

