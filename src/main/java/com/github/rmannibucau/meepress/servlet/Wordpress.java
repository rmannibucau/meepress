package com.github.rmannibucau.meepress.servlet;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import javax.enterprise.context.Dependent;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.caucho.java.WorkDir;
import com.caucho.quercus.QuercusContext;
import com.caucho.quercus.lib.curl.CurlModule;
import com.caucho.quercus.module.ModuleContext;
import com.caucho.quercus.servlet.QuercusServlet;
import com.caucho.quercus.servlet.QuercusServletImpl;
import com.caucho.vfs.FilePath;

@Dependent
@WebServlet(urlPatterns = "/", loadOnStartup = 1)
public class Wordpress extends QuercusServlet {
    @Override
    public void init(final ServletConfig config) throws ServletException {
        Locale.setDefault(Locale.ENGLISH); // otherwise json encoding fails when numbers use a comma and not a dot

        // todo: config within the context (init params etc) - com.caucho.quercus.servlet.QuercusServlet.setInitParam
        // todo: setDatasource to support h2?
        super.init(config);

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
                }
                return _quercus;
            }
        };
    }
}
