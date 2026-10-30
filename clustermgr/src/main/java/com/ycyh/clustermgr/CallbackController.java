package com.ycyh.clustermgr;
import java.util.*;
import java.time.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@RestController
public class CallbackController {
 private final JdbcTemplate db;
 public CallbackController(JdbcTemplate db){this.db=db;}
 public static class Event {public int seq;public String phase;public String message;}
 @PostMapping("/api/callbacks/{id}")
 @Transactional
 public Map<String,Boolean> event(@PathVariable String id,@RequestHeader(value="Authorization",defaultValue="")String authorization,@RequestBody Event event){
  List<Map<String,Object>> rows=db.queryForList("SELECT * FROM deploy_tasks WHERE id=? FOR UPDATE",id);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  Map<String,Object> task=rows.get(0);String actual=authorization.startsWith("Bearer ")?DeploymentService.hash(authorization.substring(7)):"";
  if(!MessageDigest.isEqual(task.get("token_hash").toString().getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  if(event.seq<1||event.message==null||event.message.length()>8000||event.phase==null||!Set.of("STARTED","PROGRESS","INSTALLED","VERIFYING","SUCCEEDED","FAILED").contains(event.phase))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  if(event.seq<=((Number)task.get("last_seq")).intValue())return Map.of("accepted",true);
  if(Set.of("QUEUED","SUCCEEDED","FAILED","REJECTED").contains(task.get("status"))||Set.of("SUCCEEDED","FAILED").contains(task.get("phase")))throw new ResponseStatusException(HttpStatus.CONFLICT);
  db.update("INSERT INTO deploy_events(id,task_id,seq,phase,message,created_at) VALUES(?,?,?,?,?,?)",id+":"+event.seq,id,event.seq,event.phase,event.message,Instant.now().toString());
  db.update("UPDATE deploy_tasks SET phase=?,last_seq=? WHERE id=?",event.phase,event.seq,id);
  return Map.of("accepted",true);
 }
}