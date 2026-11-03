package com.ycyh.oversight.v9;

import java.io.IOException;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class V9TokenFilter extends OncePerRequestFilter {
  private final V9Identity identity;

  public V9TokenFilter(V9Identity identity) {
    this.identity = identity;
  }

  public static boolean protocol(String p) {
    return p.startsWith("/api/scriptApi/")
        || p.startsWith("/scriptApi/")
        || p.startsWith("/api/approvalApi/")
        || p.startsWith("/approvalApi/")
        || p.startsWith("/taskExecute/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String p = req.getRequestURI().substring(req.getContextPath().length());
    if (!protocol(p)) {
      chain.doFilter(req, res);
      return;
    }
    try {
      req.setAttribute("v9User", identity.authenticate(req.getHeader("secretToken")));
    } catch (IllegalArgumentException e) {
      res.setStatus(200);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter().write(V9Json.fail(e.getMessage()).toString());
      return;
    }
    chain.doFilter(req, res);
  }
}
