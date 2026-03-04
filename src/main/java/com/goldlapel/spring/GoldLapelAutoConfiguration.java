package com.goldlapel.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnClass(name = "org.postgresql.Driver")
@ConditionalOnProperty(name = "goldlapel.enabled", havingValue = "true", matchIfMissing = true)
public class GoldLapelAutoConfiguration {

    @Bean
    static GoldLapelDataSourcePostProcessor goldLapelDataSourcePostProcessor(Environment environment) {
        return new GoldLapelDataSourcePostProcessor(environment);
    }
}
