package com.ycyh.oversight;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
@Service
public class ApplicationService {
 private final JdbcTemplate db;private final SshRunner runner;private final ObjectMapper json;private final TransactionTemplate tx;
 private final ThreadPoolExecutor jobs=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),new ThreadPoolExecutor.AbortPolicy());
 private final ExecutorService hosts=Executors.newFixedThreadPool(8);
 public ApplicationService(JdbcTemplate db,SshRunner runner,ObjectMapper json,PlatformTransactionManager tm){this.db=db;this.runner=runner;this.json=json;this.tx=new TransactionTemplate(tm);
  db.update("UPDATE applications SET status='UNKNOWN',reason='Service restarted; inspect remote hosts before retry' WHERE status IN ('APPROVED','RUNNING')");
  db.update("UPDATE executions SET status='UNKNOWN',reason='SERVICE_RESTART' WHERE status='RUNNING'");
 }
 public static class Target {public String hostIp,username;public Target(){}public Target(String ip,String user){hostIp=ip;username=user;}}
 public Map<String,Object> submit(String kind,String scriptUuid,String name,String content,List<Target> targets){
  if(!kind.equals("UPLOAD")&&(targets.isEmpty()||targets.size()>8))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"1-8 hosts required");
  Set<String> unique=new HashSet<>();for(Target target:targets){runner.validate(target.hostIp,target.username);if(!unique.add(target.hostIp))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Duplicate host");}
  if(kind.equals("EXECUTE")){try{UUID.fromString(scriptUuid);content=db.queryForObject("SELECT content FROM scripts WHERE id=?",String.class,scriptUuid);}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Approved script not found");}}
  else scriptUuid=UUID.randomUUID().toString();
  String id=UUID.randomUUID().toString();
  try {db.update("INSERT INTO applications(id,kind,script_uuid,name,content,targets,status,sha256,created_at) VALUES(?,?,?,?,?,?,'PENDING',?,?)",id,kind,scriptUuid,name,content,json.writeValueAsString(targets),sha256(content),Instant.now().toString());}
  catch(Exception e){throw new IllegalStateException("Cannot persist application",e);}
  return view(id);
 }
 public Map<String,Object> view(String id){
  List<Map<String,Object>> rows=db.queryForList("SELECT id,kind,script_uuid AS uuid,status,result,results,targets,sha256,created_at,approved_at,finished_at,approver,reason FROM applications WHERE id=?",id);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  Map<String,Object> app=rows.get(0);app.put("applicationId",app.remove("id"));
  try {app.put("results",app.get("results")==null?Collections.emptyMap():json.readValue(app.get("results").toString(),Map.class));app.put("targets",json.readValue(app.get("targets").toString(),List.class));}catch(Exception e){throw new IllegalStateException(e);}
  return app;
 }
 public List<Map<String,Object>> list(){return db.queryForList("SELECT id,kind,name,script_uuid,status,result,targets,sha256,created_at,approved_at,finished_at,approver,reason FROM applications ORDER BY created_at DESC LIMIT 200");}
 public Map<String,Object> detail(String id){return db.queryForMap("SELECT * FROM applications WHERE id=?",id);}
 public void decide(String id,String approver,boolean approve,String reason){
  boolean executable=Boolean.TRUE.equals(tx.execute(status->{
   Map<String,Object> app=db.queryForMap("SELECT * FROM applications WHERE id=? FOR UPDATE",id);
   if(!"PENDING".equals(app.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Application already decided");
   String note=reason==null?"":reason.substring(0,Math.min(reason.length(),500));
   if(!approve){db.update("UPDATE applications SET status='REJECTED',result=0,approver=?,approved_at=?,finished_at=?,reason=? WHERE id=?",approver,Instant.now().toString(),Instant.now().toString(),note,id);return false;}
   if(!"EXECUTE".equals(app.get("kind")))db.update("INSERT INTO scripts(id,name,script_type,content,created_at) VALUES(?,?,'SHELL',?,?)",app.get("script_uuid"),app.get("name"),app.get("content"),Instant.now().toString());
   boolean run=!"UPLOAD".equals(app.get("kind"));
   db.update("UPDATE applications SET status=?,result=?,approver=?,approved_at=?,reason=? WHERE id=?",run?"APPROVED":"SUCCEEDED",run?null:1,approver,Instant.now().toString(),note,id);
   if(!run)db.update("UPDATE applications SET finished_at=? WHERE id=?",Instant.now().toString(),id);
   return run;
  }));
  if(executable)try{jobs.execute(()->execute(id));}catch(RejectedExecutionException e){db.update("UPDATE applications SET status='FAILED',result=0,reason='Execution queue full; no remote execution started',finished_at=? WHERE id=?",Instant.now().toString(),id);}
 }
 private void execute(String id){
  try{
   Map<String,Object> app=detail(id);db.update("UPDATE applications SET status='RUNNING' WHERE id=? AND status='APPROVED'",id);
   Target[] targets=json.readValue(app.get("targets").toString(),Target[].class);
   List<Future<Integer>> futures=new ArrayList<>();for(Target target:targets)futures.add(hosts.submit(()->runHost(app,target)));
   Map<String,Integer> results=new LinkedHashMap<>();int result=1;
   for(int i=0;i<targets.length;i++){int code=futures.get(i).get();results.put(targets[i].hostIp+"@"+targets[i].username,code);if(code!=1)result=0;}
   db.update("UPDATE applications SET status=?,result=?,results=?,finished_at=? WHERE id=?",result==1?"SUCCEEDED":"FAILED",result,json.writeValueAsString(results),Instant.now().toString(),id);
  }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();db.update("UPDATE applications SET status='UNKNOWN',reason='Execution interrupted; inspect remote hosts',finished_at=? WHERE id=?",Instant.now().toString(),id);}
 }
 private int runHost(Map<String,Object> app,Target target){
  String execution=UUID.randomUUID().toString();
  db.update("INSERT INTO executions(id,script_id,host_ip,username,status,created_at) VALUES(?,?,?,?,?,?)",execution,app.get("script_uuid"),target.hostIp,target.username,"RUNNING",Instant.now().toString());
  int code=0;try{code=runner.run(app.get("content").toString(),target.hostIp,target.username);}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
  db.update("UPDATE executions SET status=?,finished_at=?,reason=? WHERE id=?",code==1?"SUCCEEDED":"FAILED",Instant.now().toString(),code==1?"EXIT_0":"NONZERO_OR_TIMEOUT",execution);return code;
 }
 public static String sha256(String text){try{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))s.append(String.format("%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
 @PreDestroy void close(){jobs.shutdownNow();hosts.shutdownNow();}
}