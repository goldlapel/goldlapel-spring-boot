package com.goldlapel.spring;

import com.goldlapel.GoldLapel;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

class GoldLapelAutoConfigurationTest {

    private final ApplicationContextRunner dataSourceRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    GoldLapelAutoConfiguration.class));

    private final ApplicationContextRunner simpleRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GoldLapelAutoConfiguration.class));

    @Test
    void autoConfiguresAndRewritesDataSource() {
        try (MockedStatic<GoldLapel> gl = mockStatic(GoldLapel.class)) {
            gl.when(() -> GoldLapel.start(eq("postgresql://localhost:5432/testdb"), any(GoldLapel.Options.class)))
                    .thenReturn("postgresql://localhost:7932/testdb");

            dataSourceRunner.withPropertyValues(
                            "spring.datasource.url=jdbc:postgresql://localhost:5432/testdb",
                            "spring.datasource.driver-class-name=org.postgresql.Driver")
                    .run(context -> {
                        assertThat(context).hasSingleBean(GoldLapelDataSourcePostProcessor.class);
                        HikariDataSource ds = context.getBean(HikariDataSource.class);
                        assertThat(ds.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:7932/testdb");
                        gl.verify(() -> GoldLapel.start(eq("postgresql://localhost:5432/testdb"), any(GoldLapel.Options.class)));
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
        try (MockedStatic<GoldLapel> gl = mockStatic(GoldLapel.class)) {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:h2:mem:testdb");

            GoldLapelProperties props = new GoldLapelProperties();
            GoldLapelDataSourcePostProcessor processor = new GoldLapelDataSourcePostProcessor(props);

            Object result = processor.postProcessAfterInitialization(ds, "dataSource");

            assertThat(result).isSameAs(ds);
            assertThat(ds.getJdbcUrl()).isEqualTo("jdbc:h2:mem:testdb");
            gl.verifyNoInteractions();
        }
    }

    @Test
    void customPortAndExtraArgs() {
        try (MockedStatic<GoldLapel> gl = mockStatic(GoldLapel.class)) {
            gl.when(() -> GoldLapel.start(any(String.class), any(GoldLapel.Options.class)))
                    .thenReturn("postgresql://localhost:9999/testdb");

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
}
