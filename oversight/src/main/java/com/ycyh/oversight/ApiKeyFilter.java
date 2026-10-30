package com.ycyh.oversight;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
public class ApiKeyFilter extends OncePerRequestFilter {
 private final byte[] key;
 public ApiKeyFilter(@Value("${oversight.api-key}") String key) {
  if(key.length()<24) throw new IllegalArgumentException("OVERSIGHT_API_KEY needs at least 24 characters");
  this.key=key.getBytes(StandardCharsets.UTF_8);
 }
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
  if(!req.getRequestURI().substring(req.getContextPath().length()).startsWith("/api/v1/")){chain.doFilter(req,res);return;}
  String value=req.getHeader("X-API-Key");
  if(value==null || !MessageDigest.isEqual(key,value.getBytes(StandardCharsets.UTF_8))) {res.sendError(401);return;}
  chain.doFilter(req,res);
 }
}