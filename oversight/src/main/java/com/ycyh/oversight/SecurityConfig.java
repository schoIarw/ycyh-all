package com.ycyh.oversight;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class SecurityConfig {
 @Bean UserDetailsService users(@Value("${oversight.admin-user}")String user,@Value("${oversight.admin-password}")String password) {
  if(password.length()<12)throw new IllegalArgumentException("ADMIN_PASSWORD needs at least 12 characters");
  return new InMemoryUserDetailsManager(User.withUsername(user).password("{bcrypt}"+new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
 }
 @Bean SecurityFilterChain security(HttpSecurity http)throws Exception {
  http.authorizeRequests().antMatchers("/error","/login","/style.css","/api/v1/**").permitAll().anyRequest().authenticated()
   .and().formLogin().loginPage("/login").defaultSuccessUrl("/",true).permitAll()
   .and().logout().logoutSuccessUrl("/login?logout")
   .and().csrf().ignoringAntMatchers("/api/v1/**");
  return http.build();
 }
}