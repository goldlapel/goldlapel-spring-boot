package com.goldlapel.spring;

import com.goldlapel.GoldLapel;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

public class GoldLapelDataSourcePostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(GoldLapelDataSourcePostProcessor.class);
    private static final String JDBC_PREFIX = "jdbc:";
    private static final String JDBC_PG_PREFIX = "jdbc:postgresql://";

    private final GoldLapelProperties properties;

    public GoldLapelDataSourcePostProcessor(GoldLapelProperties properties) {
        this.properties = properties;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof HikariDataSource ds)) {
            return bean;
        }

        String jdbcUrl = ds.getJdbcUrl();
        if (jdbcUrl == null || !jdbcUrl.startsWith(JDBC_PG_PREFIX)) {
            return bean;
        }

        String upstream = jdbcUrl.substring(JDBC_PREFIX.length());

        GoldLapel.Options options = new GoldLapel.Options()
                .port(properties.getPort());

        if (!properties.getExtraArgs().isEmpty()) {
            options.extraArgs(properties.getExtraArgs().toArray(new String[0]));
        }

        String proxyUrl = GoldLapel.start(upstream, options);
        String proxyJdbcUrl = JDBC_PREFIX + proxyUrl;

        ds.setJdbcUrl(proxyJdbcUrl);

        log.info("Gold Lapel proxy started — {} now routes through localhost:{}", beanName, properties.getPort());

        return ds;
    }
}
