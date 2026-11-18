package io.aetherstream.engine.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Hikari connection pool factory (reused by coordinator and worker). */
public final class DataSources {
    private DataSources() {}

    /** Uses PG_URL, PG_USER, PG_PASSWORD (contract 00 defaults). */
    public static HikariDataSource fromEnv(String poolName) {
        return create(env("PG_URL", "jdbc:postgresql://postgres:5432/aether"),
                env("PG_USER", "aether"), env("PG_PASSWORD", "aether"), poolName, 8);
    }

    public static HikariDataSource create(String url, String user, String password, String poolName, int maxPoolSize) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setPoolName(poolName);
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setConnectionTimeout(10_000);
        // Do not fail at construction if Postgres is not up yet; callers retry their first query.
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }
}
