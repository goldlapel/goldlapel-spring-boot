package com.goldlapel.spring;

import com.goldlapel.GoldLapel;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
                    mock(org.springframework.core.env.Environment.class));

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

            org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
            when(env.getProperty("goldlapel.port", Integer.class, 7932)).thenReturn(7932);
            when(env.getProperty("goldlapel.extra-args", "")).thenReturn("");

            GoldLapelDataSourcePostProcessor processor = new GoldLapelDataSourcePostProcessor(env);

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
}
