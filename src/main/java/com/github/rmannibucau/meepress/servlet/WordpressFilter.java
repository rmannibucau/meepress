package com.github.rmannibucau.meepress.servlet;

import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import javax.enterprise.context.Dependent;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

/**
 * Ensure the php request are routed to the wordpress servlet and others (css, images, js etc)
 * to the default servlet.
 */
@Dependent
@WebFilter(urlPatterns = "/*")
public class WordpressFilter implements Filter {
    private List<String> redirectedOnIndex;

    @Override
    public void init(final FilterConfig filterConfig) {
        redirectedOnIndex = Stream.of(System.getProperty("meepress.filter.redirectedOnIndex", "@default@").replace("@default@", "/wp-json,/page").split(","))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .collect(toList());
        filterConfig.getServletContext().log("Setup php redirections for " + redirectedOnIndex + " prefixes");
    }

    @Override
    public void doFilter(final ServletRequest servletRequest, final ServletResponse servletResponse,
                         final FilterChain filterChain) throws IOException, ServletException {
        if (!HttpServletRequest.class.isInstance(servletRequest)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        filterChain.doFilter(new RouterRequest(HttpServletRequest.class.cast(servletRequest), redirectedOnIndex), servletResponse);
    }

    private static class RouterRequest extends HttpServletRequestWrapper {
        private final List<String> redirectedOnIndex;

        private RouterRequest(final HttpServletRequest servletRequest, final List<String> redirectedOnIndex) {
            super(servletRequest);
            this.redirectedOnIndex = redirectedOnIndex;
        }

        @Override
        public String getServletPath() {
            final String servletPath = super.getServletPath();
            if (servletPath == null || servletPath.isEmpty() || "/".equals(servletPath)) {
                return "/index.php";
            }
            if (redirectedOnIndex.stream().anyMatch(prefix -> servletPath.startsWith(prefix) && servletPath.length() > prefix.length())) {
                return "/index.php";
            }
            return servletPath;
        }
    }
}
