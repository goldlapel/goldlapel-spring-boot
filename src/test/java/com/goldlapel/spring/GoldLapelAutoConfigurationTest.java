package com.goldlapel.spring;

import com.goldlapel.GoldLapel;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GoldLapelAutoConfigurationTest {

    private final ApplicationContextRunner dataSourceRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    GoldLapelAutoConfiguration.class));

    private final ApplicationContextRunner simpleRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GoldLapelAutoConfiguration.class));

    @Test
    void autoConfiguresAndRewritesDataSource() {
        try (MockedConstruction<GoldLapel> mocked = mockConstruction(GoldLapel.class,
                (mock, context) -> when(mock.startProxy()).thenReturn("postgresql://localhost:7932/testdb"))) {

            dataSourceRunner.withPropertyValues(
                            "spring.datasource.url=jdbc:postgresql://localhost:5432/testdb",
                            "spring.datasource.driver-class-name=org.postgresql.Driver")
                    .run(context -> {
                        assertThat(context).hasSingleBean(GoldLapelDataSourcePostProcessor.class);
                        HikariDataSource ds = context.getBean(HikariDataSource.class);
                        assertThat(ds.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:7932/testdb");
                        assertThat(mocked.constructed()).hasSize(1);
                        verify(mocked.constructed().get(0)).startProxy();
                    });
        }
    }

    @Test
    void disabledWhenPropertyFalse() {
        simpleRunner.withPropertyValues("goldlapel.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(GoldLapelDataSourcePostProcessor.class));
    }

    @Test
    void skipsNonPostgresDataSource() {
        try (MockedConstruction<GoldLapel> mocked = mockConstruction(GoldLapel.class)) {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:h2:mem:testdb");

            GoldLapelDataSourcePostProcessor processor = new GoldLapelDataSourcePostProcessor(
                    new GoldLapelProperties());

            Object result = processor.postProcessAfterInitialization(ds, "dataSource");

            assertThat(result).isSameAs(ds);
            assertThat(ds.getJdbcUrl()).isEqualTo("jdbc:h2:mem:testdb");
            assertThat(mocked.constructed()).isEmpty();
        }
    }

    @Test
    void customPortAndExtraArgs() {
        try (MockedConstruction<GoldLapel> mocked = mockConstruction(GoldLapel.class,
                (mock, context) -> when(mock.startProxy()).thenReturn("postgresql://localhost:9999/testdb"))) {

            dataSourceRunner.withPropertyValues(
                            "spring.datasource.url=jdbc:postgresql://localhost:5432/testdb",
                            "spring.datasource.driver-class-name=org.postgresql.Driver",
                            "goldlapel.port=9999",
                            "goldlapel.extra-args=--threshold-duration-ms,200")
                    .run(context -> {
                        HikariDataSource ds = context.getBean(HikariDataSource.class);
                        assertThat(ds.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:9999/testdb");
                    });
        }
    }

    @Test
    void notLoadedWithoutPostgresDriver() {
        simpleRunner
                .withClassLoader(new FilteredClassLoader("org.postgresql.Driver"))
                .run(context -> assertThat(context).doesNotHaveBean(GoldLapelAutoConfiguration.class));
    }

    @Test
    void multipleDataSourcesGetSeparateProxies() {
        try (MockedConstruction<GoldLapel> mocked = mockConstruction(GoldLapel.class,
                (mock, context) -> {
                    String upstream = (String) context.arguments().get(0);
                    if (upstream.contains("5432")) {
                        when(mock.startProxy()).thenReturn("postgresql://localhost:7932/db1");
                    } else {
                        when(mock.startProxy()).thenReturn("postgresql://localhost:7932/db2");
                    }
                })) {

            HikariDataSource ds1 = new HikariDataSource();
            ds1.setJdbcUrl("jdbc:postgresql://host1:5432/db1");

            HikariDataSource ds2 = new HikariDataSource();
            ds2.setJdbcUrl("jdbc:postgresql://host2:5433/db2");

            GoldLapelProperties props = new GoldLapelProperties();
            GoldLapelDataSourcePostProcessor processor = new GoldLapelDataSourcePostProcessor(props);

            processor.postProcessAfterInitialization(ds1, "primaryDataSource");
            processor.postProcessAfterInitialization(ds2, "analyticsDataSource");

            assertThat(mocked.constructed()).hasSize(2);
            assertThat(ds1.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:7932/db1");
            assertThat(ds2.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:7932/db2");

            verify(mocked.constructed().get(0)).startProxy();
            verify(mocked.constructed().get(1)).startProxy();

            assertThat(processor.getProxies()).hasSize(2);
        }
    }

    @Test
    void configMapPassedToOptions() {
        List<MockedConstruction.Context> capturedContexts = new ArrayList<>();
        try (MockedConstruction<GoldLapel> mocked = mockConstruction(GoldLapel.class,
                (mock, context) -> {
                    capturedContexts.add(context);
                    when(mock.startProxy()).thenReturn("postgresql://localhost:7932/testdb");
                })) {

            dataSourceRunner.withPropertyValues(
                            "spring.datasource.url=jdbc:postgresql://localhost:5432/testdb",
                            "spring.datasource.driver-class-name=org.postgresql.Driver",
                            "goldlapel.config.mode=butler",
                            "goldlapel.config.pool-size=30")
                    .run(context -> {
                        assertThat(context).hasSingleBean(GoldLapelDataSourcePostProcessor.class);
                        HikariDataSource ds = context.getBean(HikariDataSource.class);
                        assertThat(ds.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:7932/testdb");
                        assertThat(mocked.constructed()).hasSize(1);
                        verify(mocked.constructed().get(0)).startProxy();

                        // Verify GoldLapel was constructed with Options containing the config map
                        assertThat(capturedContexts).hasSize(1);
                        GoldLapel.Options options = (GoldLapel.Options) capturedContexts.get(0).arguments().get(1);
                        assertThat(options.config()).containsEntry("mode", "butler");
                        assertThat(options.config()).containsEntry("poolSize", "30");
                    });
        }
    }

    @Test
    void configMapWithCamelCaseKeysFromYaml() {
        try (MockedConstruction<GoldLapel> mocked = mockConstruction(GoldLapel.class,
                (mock, context) -> when(mock.startProxy()).thenReturn("postgresql://localhost:7932/testdb"))) {

            dataSourceRunner.withPropertyValues(
                            "spring.datasource.url=jdbc:postgresql://localhost:5432/testdb",
                            "spring.datasource.driver-class-name=org.postgresql.Driver",
                            "goldlapel.config.poolSize=25",
                            "goldlapel.config.disableN1=true")
                    .run(context -> {
                        HikariDataSource ds = context.getBean(HikariDataSource.class);
                        assertThat(ds.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:7932/testdb");
                        assertThat(mocked.constructed()).hasSize(1);
                        verify(mocked.constructed().get(0)).startProxy();
                    });
        }
    }

    @Test
    void configMapWithPortAndExtraArgs() {
        try (MockedConstruction<GoldLapel> mocked = mockConstruction(GoldLapel.class,
                (mock, context) -> when(mock.startProxy()).thenReturn("postgresql://localhost:9000/testdb"))) {

            dataSourceRunner.withPropertyValues(
                            "spring.datasource.url=jdbc:postgresql://localhost:5432/testdb",
                            "spring.datasource.driver-class-name=org.postgresql.Driver",
                            "goldlapel.port=9000",
                            "goldlapel.config.mode=butler",
                            "goldlapel.extra-args=--verbose")
                    .run(context -> {
                        HikariDataSource ds = context.getBean(HikariDataSource.class);
                        assertThat(ds.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:9000/testdb");
                        assertThat(mocked.constructed()).hasSize(1);
                        verify(mocked.constructed().get(0)).startProxy();
                    });
        }
    }

    @Test
    void emptyConfigMapDoesNotSetConfig() {
        try (MockedConstruction<GoldLapel> mocked = mockConstruction(GoldLapel.class,
                (mock, context) -> when(mock.startProxy()).thenReturn("postgresql://localhost:7932/testdb"))) {

            dataSourceRunner.withPropertyValues(
                            "spring.datasource.url=jdbc:postgresql://localhost:5432/testdb",
                            "spring.datasource.driver-class-name=org.postgresql.Driver")
                    .run(context -> {
                        HikariDataSource ds = context.getBean(HikariDataSource.class);
                        assertThat(ds.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:7932/testdb");
                        assertThat(mocked.constructed()).hasSize(1);
                        verify(mocked.constructed().get(0)).startProxy();
                    });
        }
    }

    @Test
    void kebabToCamelConversion() {
        assertThat(GoldLapelDataSourcePostProcessor.kebabToCamel("pool-size")).isEqualTo("poolSize");
        assertThat(GoldLapelDataSourcePostProcessor.kebabToCamel("disable-n1")).isEqualTo("disableN1");
        assertThat(GoldLapelDataSourcePostProcessor.kebabToCamel("mode")).isEqualTo("mode");
        assertThat(GoldLapelDataSourcePostProcessor.kebabToCamel("read-after-write-secs")).isEqualTo("readAfterWriteSecs");
        assertThat(GoldLapelDataSourcePostProcessor.kebabToCamel("disable-n1-cross-connection")).isEqualTo("disableN1CrossConnection");
    }

    @Test
    void normalizeCamelCaseConvertsMap() {
        Map<String, String> input = Map.of(
                "pool-size", "30",
                "mode", "butler",
                "disable-n1", "true"
        );
        Map<String, Object> result = GoldLapelDataSourcePostProcessor.normalizeCamelCase(input);
        assertThat(result).containsEntry("poolSize", "30");
        assertThat(result).containsEntry("mode", "butler");
        assertThat(result).containsEntry("disableN1", Boolean.TRUE);
    }

    @Test
    void coerceValueConvertsBooleanStrings() {
        assertThat(GoldLapelDataSourcePostProcessor.coerceValue("true")).isEqualTo(Boolean.TRUE);
        assertThat(GoldLapelDataSourcePostProcessor.coerceValue("True")).isEqualTo(Boolean.TRUE);
        assertThat(GoldLapelDataSourcePostProcessor.coerceValue("TRUE")).isEqualTo(Boolean.TRUE);
        assertThat(GoldLapelDataSourcePostProcessor.coerceValue("false")).isEqualTo(Boolean.FALSE);
        assertThat(GoldLapelDataSourcePostProcessor.coerceValue("False")).isEqualTo(Boolean.FALSE);
        assertThat(GoldLapelDataSourcePostProcessor.coerceValue("FALSE")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void coerceValueSplitsCommaSeparatedStrings() {
        Object result = GoldLapelDataSourcePostProcessor.coerceValue("users,orders,products");
        assertThat(result).isEqualTo(List.of("users", "orders", "products"));
    }

    @Test
    void coerceValueSplitsCommaSeparatedWithSpaces() {
        Object result = GoldLapelDataSourcePostProcessor.coerceValue("users , orders , products");
        assertThat(result).isEqualTo(List.of("users", "orders", "products"));
    }

    @Test
    void coerceValueLeavesPlainStringsAlone() {
        assertThat(GoldLapelDataSourcePostProcessor.coerceValue("butler")).isEqualTo("butler");
        assertThat(GoldLapelDataSourcePostProcessor.coerceValue("30")).isEqualTo("30");
        assertThat(GoldLapelDataSourcePostProcessor.coerceValue("redis://localhost:6379")).isEqualTo("redis://localhost:6379");
    }

    @Test
    void coerceValueHandlesNull() {
        assertThat(GoldLapelDataSourcePostProcessor.coerceValue(null)).isNull();
    }

    @Test
    void coerceValueHandlesTrailingComma() {
        Object result = GoldLapelDataSourcePostProcessor.coerceValue("users,orders,");
        assertThat(result).isEqualTo(List.of("users", "orders"));
    }

    @Test
    void normalizeCamelCaseCoercesBooleanAndListValues() {
        Map<String, String> input = Map.of(
                "disable-n1", "true",
                "enable-coalescing", "false",
                "exclude-tables", "users,orders",
                "pool-size", "30",
                "mode", "butler"
        );
        Map<String, Object> result = GoldLapelDataSourcePostProcessor.normalizeCamelCase(input);
        assertThat(result).containsEntry("disableN1", Boolean.TRUE);
        assertThat(result).containsEntry("enableCoalescing", Boolean.FALSE);
        assertThat(result).containsEntry("excludeTables", List.of("users", "orders"));
        assertThat(result).containsEntry("poolSize", "30");
        assertThat(result).containsEntry("mode", "butler");
    }
}
