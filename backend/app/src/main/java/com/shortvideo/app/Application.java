package com.shortvideo.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** The modular monolith. One process, one connection pool, module-owned schemas. */
@SpringBootApplication(scanBasePackages = "com.shortvideo")
@ConfigurationPropertiesScan(basePackages = "com.shortvideo")
@EntityScan("com.shortvideo")
@EnableJpaRepositories("com.shortvideo")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
