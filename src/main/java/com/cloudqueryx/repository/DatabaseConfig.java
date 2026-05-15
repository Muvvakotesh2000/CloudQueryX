package com.cloudqueryx.repository;

import com.cloudqueryx.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public final class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);
    private static volatile DatabaseConfig instance;

    private final HikariDataSource dataSource;

    private DatabaseConfig(AppConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.dbUrl());
        hikari.setUsername(config.dbUser());
        hikari.setPassword(config.dbPassword());
        hikari.setMaximumPoolSize(config.dbPoolSize());
        hikari.setConnectionTimeout(config.dbPoolTimeout());
        hikari.setMinimumIdle(0);
        hikari.setInitializationFailTimeout(-1);
        hikari.setValidationTimeout(5_000);
        hikari.setIdleTimeout(300_000);
        hikari.setMaxLifetime(600_000);
        hikari.setPoolName("CloudQueryX-Pool");
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikari.addDataSourceProperty("useServerPrepStmts", "true");

        this.dataSource = new HikariDataSource(hikari);
        log.info("Connection pool initialized: {} (max pool size: {})", config.dbUrl(), config.dbPoolSize());
    }

    public static DatabaseConfig getInstance() {
        if (instance == null) {
            synchronized (DatabaseConfig.class) {
                if (instance == null) {
                    instance = new DatabaseConfig(AppConfig.getInstance());
                }
            }
        }
        return instance;
    }

    public static DatabaseConfig create(AppConfig config) {
        return new DatabaseConfig(config);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Connection pool closed");
        }
    }
}
