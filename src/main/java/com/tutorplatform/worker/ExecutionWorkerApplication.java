package com.tutorplatform.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExecutionWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExecutionWorkerApplication.class, args);
    }
}
