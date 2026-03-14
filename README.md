# goldlapel-spring-boot-starter

Spring Boot auto-configuration for [Gold Lapel](https://goldlapel.com) — the self-optimizing Postgres proxy.

Add the dependency and Gold Lapel starts automatically. Your existing `application.yml` datasource config works unchanged.

## Install

```xml
<dependency>
    <groupId>com.goldlapel</groupId>
    <artifactId>goldlapel-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

That's it. Gold Lapel intercepts your HikariCP datasource on startup, watches your query patterns, and automatically optimizes your database.

## Configuration

Optional proxy settings via `application.yml`:

```yaml
goldlapel:
  port: 9000                    # proxy listen port (default: 7932)
  config:
    mode: butler                # bellhop (free) or butler (paid)
    pool-size: 30               # upstream connection pool size
    disable-n1: true            # disable N+1 detection
    refresh-interval-secs: 120  # matview refresh interval
  extra-args:
    - "--threshold-duration-ms"
    - "200"
```

Or `application.properties`:

```properties
goldlapel.port=9000
goldlapel.config.mode=butler
goldlapel.config.pool-size=30
goldlapel.config.disable-n1=true
goldlapel.config.refresh-interval-secs=120
goldlapel.extra-args=--threshold-duration-ms,200
```

The `config` map accepts any Gold Lapel CLI flag as a camelCase key. Spring Boot's relaxed binding means `pool-size`, `poolSize`, and `POOL_SIZE` all work. Keys are normalized to camelCase before being passed to the proxy.

To disable Gold Lapel without removing the dependency:

```yaml
goldlapel:
  enabled: false
```

## Multiple DataSources

Each datasource needs a different proxy port — otherwise the second database will route through the first's proxy:

```yaml
spring:
  datasource:
    primary:
      url: jdbc:postgresql://db1.example.com:5432/myapp
    analytics:
      url: jdbc:postgresql://db2.example.com:5432/analytics
```

Configure separate ports per datasource using the Gold Lapel Java API directly in your `@Configuration` class, since Spring Boot properties apply globally.

## Requirements

- Java 17+
- Spring Boot 3.x
- HikariCP (Spring Boot's default connection pool)
- PostgreSQL JDBC driver on the classpath

## How It Works

When Spring Boot creates a `HikariDataSource`, Gold Lapel's `BeanPostProcessor`:

1. Strips `jdbc:` from the JDBC URL to get a standard PostgreSQL URL
2. Starts the Gold Lapel proxy via [`GoldLapel.start()`](https://github.com/goldlapel/goldlapel-java)
3. Rewrites the datasource URL to route through the proxy (`jdbc:postgresql://127.0.0.1:7932/...`)

This happens before HikariCP opens any pool connections, so the rewrite is transparent. Everything else — JPA, JDBC, jOOQ, MyBatis — works exactly as before, just faster.

## License

Proprietary. See [goldlapel.com](https://goldlapel.com) for licensing.
