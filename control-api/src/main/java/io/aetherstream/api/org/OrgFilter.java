package io.aetherstream.api.org;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrgFilter extends OncePerRequestFilter {
    private static final Pattern VALID_ORG = Pattern.compile("[A-Za-z0-9-]{1,40}");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api") && !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Enumeration<String> values = request.getHeaders("X-Org");
        String org = values.hasMoreElements() ? values.nextElement() : "demo";
        if (values.hasMoreElements() || !VALID_ORG.matcher(org).matches()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"INVALID_ORG\",\"message\":\"X-Org must contain 1-40 letters, digits or hyphens\"}");
            return;
        }
        request.setAttribute(OrgContext.ATTRIBUTE, org);
        chain.doFilter(request, response);
    }
}
