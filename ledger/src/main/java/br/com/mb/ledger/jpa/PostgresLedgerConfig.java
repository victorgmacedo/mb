package br.com.mb.ledger.jpa;

import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = LedgerBalanceRepository.class)
@ComponentScan(basePackageClasses = JpaLedger.class)
public class PostgresLedgerConfig {

    @Bean
    DataSource dataSource() {
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(env("MB_LEDGER_JDBC_URL", "jdbc:postgresql://localhost:5432/mb"));
        dataSource.setUsername(env("MB_LEDGER_USERNAME", "mb"));
        dataSource.setPassword(env("MB_LEDGER_PASSWORD", "mb"));
        return dataSource;
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("br.com.mb.ledger.jpa");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(jpaProperties());
        return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    private static HashMap<String, Object> jpaProperties() {
        var properties = new HashMap<String, Object>();
        properties.put("hibernate.hbm2ddl.auto", env("MB_LEDGER_HBM2DDL_AUTO", "update"));
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("hibernate.show_sql", env("MB_LEDGER_SHOW_SQL", "false"));
        properties.put("hibernate.bytecode.provider", "none");
        return properties;
    }

    private static String env(String name, String defaultValue) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
