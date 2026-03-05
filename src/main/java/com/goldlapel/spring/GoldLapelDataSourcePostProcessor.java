package com.goldlapel.spring;

import com.goldlapel.GoldLapel;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

public class GoldLapelDataSourcePostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(GoldLapelDataSourcePostProcessor.class);
    private static final String JDBC_PREFIX = "jdbc:";
    private static final String JDBC_PG_PREFIX = "jdbc:postgresql://";

    private final Environment environment;
    private final List<GoldLapel> proxies = new ArrayList<>();

    public GoldLapelDataSourcePostProcessor(Environment environment) {
        this.environment = environment;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (GoldLapel proxy : proxies) {
                proxy.stopProxy();
            }
        }));
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

        int port = environment.getProperty("goldlapel.port", Integer.class, 7932);
        String extraArgsStr = environment.getProperty("goldlapel.extra-args", "");

        GoldLapel.Options options = new GoldLapel.Options().port(port);

        if (!extraArgsStr.isEmpty()) {
            options.extraArgs(extraArgsStr.split(","));
        }

        GoldLapel proxy = new GoldLapel(upstream, options);
        String proxyUrl;
        try {
            proxyUrl = proxy.startProxy();
        } catch (RuntimeException e) {
            String safeUpstream = upstream.replaceAll("://.*@", "://***@");
            throw new RuntimeException(
                    "Gold Lapel failed to start proxy for datasource '" + beanName +
                    "' (upstream: " + safeUpstream + ", port: " + port + ")", e);
        }

        proxies.add(proxy);
        String proxyJdbcUrl = JDBC_PREFIX + proxyUrl;
        ds.setJdbcUrl(proxyJdbcUrl);

        log.info("Gold Lapel proxy started — {} now routes through localhost:{}", beanName, port);

        return ds;
    }

    // Visible for testing
    List<GoldLapel> getProxies() {
        return proxies;
    }
}
