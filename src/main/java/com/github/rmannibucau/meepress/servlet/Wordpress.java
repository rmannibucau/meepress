package com.github.rmannibucau.meepress.servlet;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toMap;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Stream;

import javax.enterprise.context.Dependent;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import com.caucho.java.WorkDir;
import com.caucho.quercus.QuercusContext;
import com.caucho.quercus.env.Env;
import com.caucho.quercus.lib.curl.CurlModule;
import com.caucho.quercus.lib.db.Mysqli;
import com.caucho.quercus.module.ModuleContext;
import com.caucho.quercus.servlet.QuercusServlet;
import com.caucho.quercus.servlet.QuercusServletImpl;
import com.caucho.vfs.FilePath;

@Dependent
@WebServlet(urlPatterns = "/", loadOnStartup = 1)
public class Wordpress extends QuercusServlet {
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduler;
    private String[] sqlScheduledSql;
    private volatile QuercusContext quercus;

    @Override
    public void init(final ServletConfig config) throws ServletException {
        Locale.setDefault(Locale.ENGLISH); // otherwise json encoding fails when numbers use a comma and not a dot

        // todo: config within the context (init params etc) - com.caucho.quercus.servlet.QuercusServlet.setInitParam
        // todo: setDatasource to support h2?
        super.init(config);

        final long scheduleMs = Long.getLong("meepress.scheduler.delayMs");
        sqlScheduledSql = Stream.of(System.getProperty("meepress.scheduler.sql"))
            .filter(Objects::nonNull)
            .map(String::trim)
            .flatMap(it -> Stream.of(it.split("\n")).map(String::trim).filter(v -> !v.isEmpty()))
            .toArray(String[]::new);
        if (sqlScheduledSql.length > 0) {
            executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable r) {
                    return new Thread(r, getClass().getName() + "-scheduler");
                }
            });
            scheduler = executor.scheduleAtFixedRate(this::scheduled, scheduleMs, scheduleMs, MILLISECONDS);
        }

        // for tomcat like system ensure it uses the right work folder
        final String base = System.getProperty("catalina.base", System.getProperty("meecrowave.base", "."));
        if (base != null) {
            final File work = new File(base, "work");
            if (!work.exists() && !work.mkdirs()) {
                throw new IllegalStateException("Can't create " + work);
            }
            WorkDir.setLocalWorkDir(new FilePath(work.getAbsolutePath()));
        }
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.cancel(true);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.destroy();
    }

    @Override
    public void service(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
        if (request.getRequestURI().substring(request.getContextPath().length()).equals("/wp-config.php")) { // secure it
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        super.service(request, response);
    }

    @Override
    protected QuercusServletImpl getQuercusServlet(boolean isResin) {
        return new QuercusServletImpl() {
            @Override
            protected QuercusContext getQuercus() {
                if (_quercus == null) {
                    _quercus = new QuercusContext() {
                        @Override // patch curl module with some options customizations
                        public ModuleContext getLocalContext(final ClassLoader loader) {
                            final ModuleContext localContext = new ModuleContext(null, loader);
                            localContext.addModule(CurlModule.class.getName(), new PatchedCurlModule());
                            localContext.init(); // will skip default curl module
                            return localContext;
                        }
                    };
                    quercus = _quercus;
                }
                return _quercus;
            }
        };
    }

    // todo: abstract scheduled tasks with cdi
    private void scheduled() {
        final InputStream config = getServletContext().getResourceAsStream("/wp-config.php");
        if (config == null) {
            return;
        }
        final Map<String, String> configs;
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(config))) {
            // extract define('xxx', 'vvv'); values
            configs = reader.lines()
                    .map(String::trim)
                    .filter(it -> it.startsWith("define(") && it.endsWith(");"))
                    .map(it -> it.substring("define(".length(), it.length() - ");".length()))
                    .map(it -> it.split(","))
                    .filter(it -> it.length == 2)
                    .collect(toMap(it -> dropQuotes(it[0]), it -> dropQuotes(it[1])));
        } catch (final IOException e) {
            getServletContext().log("Can't read wp-config.pgp", e);
            return;
        }
        if (!Boolean.parseBoolean(configs.get("DISABLE_WP_CRON"))) {
            return;
        }

        if (sqlScheduledSql != null) { // reuse Mysql to get the same instance of database
            final QuercusContext quercus = getQuercus();
            final Env env = new Env(quercus, null, null, null, null);
            try {
                final Connection connection = getDatabaseConnection(configs, env);
                try (final Statement statement = connection.createStatement()) {
                    Stream.of(sqlScheduledSql).forEach(sql -> {
                        try {
                            statement.execute(sql);
                        } catch (final SQLException e) {
                            getServletContext().log(e.getMessage(), e);
                        }
                    });
                }
            } catch (final SQLException e) {
                getServletContext().log(e.getMessage(), e);
            } finally {
                env.close();
            }
        }
    }

    private Connection getDatabaseConnection(final Map<String, String> configs, final Env env) throws SQLException {
        final DataSource database = getQuercus().getDatabase();
        if (database != null) {
            final Connection connection = database.getConnection();
            env.addCleanup(connection::close);
            return connection;
        }

        final Mysqli mysqli = new Mysqli(
                env,
                env.createUnicodeBuilder().append(configs.get("DB_HOST")),
                env.createUnicodeBuilder().append(configs.get("DB_USER")),
                env.createUnicodeBuilder().append(configs.get("DB_PASSWORD")),
                configs.get("DB_NAME"),
                ofNullable(configs.get("DB_PORT")).map(Integer::parseInt).orElse(3306),
                env.createUnicodeBuilder());
        return mysqli.getConnection(env);
    }

    private QuercusContext getQuercus() {
        QuercusContext quercus = this.quercus;
        if (quercus == null) {
            getQuercusServlet(false);
            quercus = this.quercus;
        }
        return quercus;
    }

    private String dropQuotes(final String raw) {
        final String value = raw.trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
