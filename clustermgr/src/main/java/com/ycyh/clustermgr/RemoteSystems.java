package com.ycyh.clustermgr;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
@Component
public class RemoteSystems {
 private final Settings settings;private final RestTemplate http;private final ObjectMapper json;
 public RemoteSystems(Settings settings,ObjectMapper json){this.settings=settings;this.json=json;SimpleClientHttpRequestFactory f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(15000);f.setReadTimeout(30000);http=new RestTemplate(f);}
 public static class Software {public String name,path,sha256;public long size;}
 public List<Software> packages(){Software[] a=http.getForObject(settings.warehouse+"/_catalog",Software[].class);return a==null?Collections.emptyList():Arrays.asList(a);}
 private HttpHeaders headers(){HttpHeaders h=new HttpHeaders();h.set("X-API-Key",settings.key);return h;}
 public Map<String,Object> submit(String clusterId,String script,List<Map<String,String>> targets)throws Exception{
  HttpHeaders h=headers();h.setContentType(MediaType.MULTIPART_FORM_DATA);MultiValueMap<String,Object> body=new LinkedMultiValueMap<>();body.add("targets",json.writeValueAsString(targets));
  body.add("file",new ByteArrayResource(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)){@Override public String getFilename(){return "install-es-"+clusterId+".sh";}});
  return call(settings.oversight+"/api/v1/deployments",HttpMethod.POST,new HttpEntity<>(body,h));
 }
 public Map<String,Object> application(String id){return call(settings.oversight+"/api/v1/applications/"+id,HttpMethod.GET,new HttpEntity<>(headers()));}
 @SuppressWarnings("unchecked") private Map<String,Object> call(String url,HttpMethod method,HttpEntity<?> entity){Map<String,Object> result=http.exchange(url,method,entity,Map.class).getBody();if(result==null)throw new IllegalStateException("No oversight response");return result;}
}