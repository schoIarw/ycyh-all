package com.ycyh.clustermgr;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class SecurityConfig {
 @Bean UserDetailsService users(@Value("${clustermgr.admin-user}")String user,@Value("${clustermgr.admin-password}")String password) {
  if(password.length()<12)throw new IllegalArgumentException("ADMIN_PASSWORD needs at least 12 characters");
  return new InMemoryUserDetailsManager(User.withUsername(user).password("{bcrypt}"+new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
 }
 @Bean SecurityFilterChain security(HttpSecurity http)throws Exception {
  http.authorizeRequests().antMatchers("/error","/login","/style.css","/api/callbacks/*").permitAll().anyRequest().authenticated()
   .and().formLogin().loginPage("/login").defaultSuccessUrl("/",true).permitAll()
   .and().logout().logoutSuccessUrl("/login?logout")
   .and().csrf().ignoringAntMatchers("/api/callbacks/*");
  return http.build();
 }
}