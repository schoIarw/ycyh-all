package com.ycyh.clustermgr;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
@Component
public class ScriptRenderer {
 private final String template;private final ObjectMapper json;
 public ScriptRenderer(ObjectMapper json)throws Exception{this.json=json;try(java.io.InputStream in=new ClassPathResource("scripts/install-es.sh").getInputStream()){template=StreamUtils.copyToString(in,StandardCharsets.UTF_8);}}
 public String render(Map<String,Object> config)throws Exception {
  return template.replace("__CONFIG_BASE64__",Base64.getEncoder().encodeToString(json.writeValueAsBytes(config)));
 }
}