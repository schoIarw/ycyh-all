package com.ycyh.clustermgr;
import java.util.*;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class Settings {
 public final String oversight,warehouse,callback,key;
 public final Map<String,String> hosts=new LinkedHashMap<>();
 public final boolean insecure;
 public Settings(@Value("${clustermgr.oversight-url}")String oversight,@Value("${clustermgr.warehouse-url}")String warehouse,
 @Value("${clustermgr.callback-base-url}")String callback,@Value("${clustermgr.oversight-key}")String key,
 @Value("${clustermgr.hosts}")String hosts,@Value("${clustermgr.allow-insecure-es}")boolean insecure) {
  this.oversight=base(oversight);this.warehouse=base(warehouse);this.callback=base(callback);this.key=key;this.insecure=insecure;
  for(String host:hosts.split(",")){String[] p=host.trim().split("@");if(p.length!=2||!p[0].matches("[0-9.]+")||!p[1].matches("[a-z_][a-z0-9_-]{0,31}")||p[1].equals("root"))throw new IllegalArgumentException("Use IPv4@non-root-user host entries");this.hosts.put(p[0],p[1]);}
 }
 private static String base(String value){URI uri=URI.create(value);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException("Invalid service URL");return value.replaceAll("/+$","");}
}