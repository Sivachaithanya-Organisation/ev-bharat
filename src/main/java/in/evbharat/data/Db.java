package in.evbharat.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** PostgreSQL pool, created lazily so the read-only parts of the site work even when the database is down. */
public final class Db {

    private static HikariDataSource ds;

    private Db() {}

    public static synchronized DataSource get() {
        if (ds == null) {
            HikariConfig c = new HikariConfig();
            c.setJdbcUrl(env("DB_URL", "jdbc:postgresql://localhost:5432/evbharat"));
            c.setUsername(env("DB_USER", "evbharat"));
            c.setPassword(env("DB_PASSWORD", "evbharat"));
            c.setMaximumPoolSize(10);
            c.setDriverClassName("org.postgresql.Driver");
            HikariDataSource created = new HikariDataSource(c);
            try {
                createSchema(created);
            } catch (RuntimeException e) {
                created.close();
                throw e;
            }
            ds = created;
        }
        return ds;
    }

    public static synchronized void close() {
        if (ds != null) {
            ds.close();
            ds = null;
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static void createSchema(DataSource source) {
        try (Connection con = source.getConnection(); Statement st = con.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS users (
                  id BIGSERIAL PRIMARY KEY,
                  email TEXT NOT NULL UNIQUE,
                  name TEXT NOT NULL,
                  password_hash TEXT NOT NULL,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now())""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS charge_points (
                  id BIGSERIAL PRIMARY KEY,
                  owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  title TEXT NOT NULL,
                  city TEXT NOT NULL,
                  state TEXT NOT NULL,
                  lat DOUBLE PRECISION NOT NULL,
                  lng DOUBLE PRECISION NOT NULL,
                  connector TEXT NOT NULL,
                  power_kw INT NOT NULL,
                  price_per_kwh NUMERIC(8,2) NOT NULL,
                  phone TEXT NOT NULL,
                  notes TEXT NOT NULL DEFAULT '',
                  active BOOLEAN NOT NULL DEFAULT TRUE,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now())""");
            st.execute("CREATE INDEX IF NOT EXISTS charge_points_owner_idx ON charge_points(owner_id)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise the database schema", e);
        }
    }
}
