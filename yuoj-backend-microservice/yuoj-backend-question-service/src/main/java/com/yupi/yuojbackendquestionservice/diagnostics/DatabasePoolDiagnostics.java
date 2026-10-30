package com.yupi.yuojbackendquestionservice.diagnostics;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Opt-in sampling on a dedicated daemon; never borrows a database connection. */
@Component
@ConditionalOnProperty(name = "judge.diagnostics.enabled", havingValue = "true")
public class DatabasePoolDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(DatabasePoolDiagnostics.class);
    private final DataSource dataSource;
    private ScheduledExecutorService sampler;

    public DatabasePoolDiagnostics(DataSource dataSource) { this.dataSource = dataSource; }

    @PostConstruct
    public void start() throws SQLException {
        HikariDataSource pool;
        if (dataSource instanceof HikariDataSource) {
            pool = (HikariDataSource) dataSource;
        } else if (dataSource.isWrapperFor(HikariDataSource.class)) {
            pool = dataSource.unwrap(HikariDataSource.class);
        } else {
            LOG.warn("DB_POOL unsupportedDataSource={}", dataSource.getClass().getName());
            return;
        }
        LOG.info("DB_POOL enabled pool={} max={} minimumIdle={} connectionTimeoutMs={}",
                pool.getPoolName(), pool.getMaximumPoolSize(), pool.getMinimumIdle(), pool.getConnectionTimeout());
        sampler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "db-pool-diagnostics");
            thread.setDaemon(true);
            return thread;
        });
        sampler.scheduleWithFixedDelay(() -> {
            try {
                HikariPoolMXBean stats = pool.getHikariPoolMXBean();
                if (stats != null) {
                    LOG.info("DB_POOL pool={} active={} idle={} total={} waiting={} max={}",
                            pool.getPoolName(), stats.getActiveConnections(), stats.getIdleConnections(),
                            stats.getTotalConnections(), stats.getThreadsAwaitingConnection(), pool.getMaximumPoolSize());
                }
            } catch (RuntimeException e) {
                LOG.warn("DB_POOL sampleFailed exceptionType={}", e.getClass().getName());
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (sampler != null) { sampler.shutdownNow(); }
    }
}
