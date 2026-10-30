package com.ycyh.clustermgr;
import java.util.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@RestController
@RequestMapping("/api")
public class ApiController {
 private final DeploymentService service;private final Settings settings;private final RemoteSystems remote;
 public ApiController(DeploymentService service,Settings settings,RemoteSystems remote){this.service=service;this.settings=settings;this.remote=remote;}
 @GetMapping("/me")public Map<String,String> me(Principal p){return Map.of("username",p.getName());}
 @GetMapping("/hosts")public List<Map<String,String>> hosts(){List<Map<String,String>> hosts=new ArrayList<>();settings.hosts.forEach((ip,user)->hosts.add(Map.of("ip",ip,"username",user)));return hosts;}
 @GetMapping("/packages")public List<RemoteSystems.Software> packages(){return remote.packages();}
 @GetMapping("/clusters")public List<Map<String,Object>> clusters(){return service.list();}
 @PostMapping("/clusters")public Map<String,String> create(@RequestBody DeploymentService.Create request){return Map.of("id",service.create(request));}
 @GetMapping("/clusters/{id}/events")public List<Map<String,Object>> events(@PathVariable String id){return service.events(id);}
 @ExceptionHandler(ResponseStatusException.class)
 public org.springframework.http.ResponseEntity<Map<String,String>> invalid(ResponseStatusException ex){return org.springframework.http.ResponseEntity.status(ex.getStatus()).body(Map.of("message",Optional.ofNullable(ex.getReason()).orElse("请求无效")));}
 @ExceptionHandler(Exception.class)@ResponseStatus(HttpStatus.BAD_GATEWAY)
 public Map<String,String> unavailable(Exception ex){return Map.of("message","依赖服务或数据库不可用，请检查服务日志与配置");}
}