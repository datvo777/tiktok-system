package com.shortvideo.upload.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lets {@code @SchedulerLock} jobs run on one instance at a time. The lock lives in the
 * database, so it holds no connection while a job runs; {@code usingDbTime} keeps the
 * expiry on the database clock rather than trusting each instance's own.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class UploadSchedulingConfig {

    @Bean
    LockProvider uploadLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("upload.shedlock")
                .usingDbTime()
                .build());
    }
}
