package com.goldlapel.spring;

import com.goldlapel.GoldLapel;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GoldLapelDataSourcePostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(GoldLapelDataSourcePostProcessor.class);
    private static final String JDBC_PREFIX = "jdbc:";
    private static final String JDBC_PG_PREFIX = "jdbc:postgresql://";

    private final GoldLapelProperties properties;
    private final List<GoldLapel> proxies = new ArrayList<>();

    public GoldLapelDataSourcePostProcessor(GoldLapelProperties properties) {
        this.properties = properties;
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

        int port = properties.getPort();
        String extraArgsStr = properties.getExtraArgs();
        Map<String, String> configMap = properties.getConfig();

        GoldLapel.Options options = new GoldLapel.Options().port(port);

        if (configMap != null && !configMap.isEmpty()) {
            options.config(normalizeCamelCase(configMap));
        }

        if (extraArgsStr != null && !extraArgsStr.isEmpty()) {
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

    // Convert kebab-case keys to camelCase and coerce String values to their
    // native types so the Java wrapper's configToArgs() gets what it expects.
    //
    // Spring Boot YAML properties always arrive as Strings (e.g. "true" not true),
    // but the wrapper does instanceof Boolean / instanceof List checks.
    //
    //   goldlapel.config.pool-size=30        -> poolSize: "30"       (stays String)
    //   goldlapel.config.disable-n1=true      -> disableN1: true     (Boolean)
    //   goldlapel.config.exclude-tables=a,b   -> excludeTables: ["a","b"] (List)
    static Map<String, Object> normalizeCamelCase(Map<String, String> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            result.put(kebabToCamel(entry.getKey()), coerceValue(entry.getValue()));
        }
        return result;
    }

    // Coerce Spring Boot String property values to the native types the Java
    // wrapper expects:
    //   "true" / "false"  -> Boolean  (for boolean flag keys like disableN1)
    //   "a,b,c"           -> List     (for list keys like excludeTables, replica)
    //   everything else   -> String   (numeric values stay as strings; the wrapper
    //                                  calls .toString() on them anyway)
    static Object coerceValue(String value) {
        if (value == null) {
            return null;
        }
        if (value.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (value.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        if (value.contains(",")) {
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return value;
    }

    static String kebabToCamel(String key) {
        if (!key.contains("-")) {
            return key;
        }
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
