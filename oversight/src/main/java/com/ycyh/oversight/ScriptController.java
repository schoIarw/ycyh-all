package com.ycyh.oversight;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1")
public class ScriptController {
 private final ApplicationService service;private final ObjectMapper json;
 public ScriptController(ApplicationService service,ObjectMapper json){this.service=service;this.json=json;}
 @PostMapping(value="/scripts",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 @ResponseStatus(HttpStatus.ACCEPTED)
 public Map<String,Object> upload(@RequestParam("file")MultipartFile file,@RequestParam(defaultValue="SHELL")String type)throws Exception {
  return service.submit("UPLOAD",null,name(file),read(file,type),Collections.emptyList());
 }
 public static class ExecuteRequest {public String scriptUuid;public String hostIp;public String username;}
 @PostMapping("/scripts/execute")@ResponseStatus(HttpStatus.ACCEPTED)
 public Map<String,Object> execute(@RequestBody ExecuteRequest req){return service.submit("EXECUTE",req.scriptUuid,"execute",null,List.of(new ApplicationService.Target(req.hostIp,req.username)));}
 @PostMapping(value="/deployments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 @ResponseStatus(HttpStatus.ACCEPTED)
 public Map<String,Object> deploy(@RequestParam("file")MultipartFile file,@RequestParam("targets")String targets)throws Exception {
  ApplicationService.Target[] parsed=json.readValue(targets,ApplicationService.Target[].class);
  return service.submit("DEPLOYMENT",null,name(file),read(file,"SHELL"),Arrays.asList(parsed));
 }
 @GetMapping("/applications/{id}") public Map<String,Object> application(@PathVariable String id){return service.view(id);}
 @GetMapping(value="/applications/{id}/result",produces=MediaType.TEXT_PLAIN_VALUE)
 public ResponseEntity<String> result(@PathVariable String id){Map<String,Object> app=service.view(id);Object result=app.get("result");return result==null?ResponseEntity.status(202).body("PENDING"):ResponseEntity.ok(result.toString());}
 @ExceptionHandler(IllegalArgumentException.class)
 @ResponseStatus(HttpStatus.BAD_REQUEST)
 public Map<String,String> invalid(IllegalArgumentException ex){return Map.of("message","Invalid script, host or user");}
 private String name(MultipartFile file){String name=Optional.ofNullable(file.getOriginalFilename()).orElse("script.sh");return name.substring(0,Math.min(200,name.length()));}
 private String read(MultipartFile file,String type)throws Exception {
  if(!"SHELL".equals(type)||file.isEmpty()||file.getSize()>1048576)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid SHELL file");
  String text=new String(file.getBytes(),java.nio.charset.StandardCharsets.UTF_8);if(text.indexOf(0)>=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);return text;
 }
}