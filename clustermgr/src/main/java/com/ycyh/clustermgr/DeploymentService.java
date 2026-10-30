package com.ycyh.clustermgr;
import java.util.*;
import java.time.Instant;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Service
public class DeploymentService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final Settings settings;private final RemoteSystems remote;private final ScriptRenderer renderer;
 private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),new ThreadPoolExecutor.AbortPolicy());
 public DeploymentService(JdbcTemplate db,PlatformTransactionManager tm,Settings settings,RemoteSystems remote,ScriptRenderer renderer){
  this.db=db;this.tx=new TransactionTemplate(tm);this.settings=settings;this.remote=remote;this.renderer=renderer;
  db.update("UPDATE deploy_tasks SET status='UNKNOWN',detail='Service restarted before application ID was persisted; inspect oversight applications' WHERE status='QUEUED'");
 }
 @PreDestroy void close(){workers.shutdownNow();}
 public static class Create {public String name;public String packagePath;public List<String> hostIps;}
 public synchronized String create(Create req) {
  if(!settings.insecure)throw bad("当前安装模板仅支持隔离验证环境；请显式配置 ALLOW_INSECURE_ES=true");
  if(req.name==null||!req.name.matches("[a-z][a-z0-9-]{0,47}")||req.hostIps==null||req.hostIps.isEmpty()||req.hostIps.size()>8||new HashSet<>(req.hostIps).size()!=req.hostIps.size()||!settings.hosts.keySet().containsAll(req.hostIps))throw bad("集群名、主机列表不合法（1—8台且不可重复）");
  RemoteSystems.Software software=remote.packages().stream().filter(p->p.path.equals(req.packagePath)).findFirst().orElseThrow(()->bad("仓库中不存在此软件包"));
  if(software.sha256==null||!software.sha256.matches("[a-f0-9]{64}")||software.path.contains("..")||software.path.startsWith("/")||!software.path.endsWith(".tar.gz")||!software.path.matches("[a-zA-Z0-9_./%~-]+"))throw bad("软件包路径或校验和不合法");
  if(db.queryForObject("SELECT COUNT(*) FROM clusters WHERE name=?",Integer.class,req.name)>0)throw bad("集群名已存在；失败部署请先核查远端目录");
  if(workers.getQueue().remainingCapacity()==0)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"部署队列已满");
  String clusterId=UUID.randomUUID().toString();List<Map<String,Object>> configs=new ArrayList<>();List<Map<String,String>> targets=new ArrayList<>();
  tx.executeWithoutResult(status->{
   db.update("INSERT INTO clusters(id,name,package_path,sha256,created_at) VALUES(?,?,?,?,?)",clusterId,req.name,software.path,software.sha256,Instant.now().toString());
   for(int i=0;i<req.hostIps.size();i++){
    String host=req.hostIps.get(i),id=UUID.randomUUID().toString(),token=UUID.randomUUID().toString()+UUID.randomUUID();
    db.update("INSERT INTO deploy_tasks(id,cluster_id,host_ip,username,token_hash,status,phase,created_at) VALUES(?,?,?,?,?,'QUEUED','QUEUED',?)",id,clusterId,host,settings.hosts.get(host),hash(token),Instant.now().toString());
    Map<String,Object> c=new LinkedHashMap<>();c.put("task_id",id);c.put("token",token);c.put("callback_url",settings.callback+"/api/callbacks/"+id);c.put("package_url",settings.warehouse+"/"+software.path);c.put("sha256",software.sha256);c.put("cluster_name",req.name);c.put("node_name","es-"+(i+1));c.put("host_ip",host);c.put("expected_nodes",req.hostIps.size());c.put("seed_hosts",req.hostIps);List<String> names=new ArrayList<>();for(int n=1;n<=req.hostIps.size();n++)names.add("es-"+n);c.put("master_nodes",names);configs.add(c);targets.add(Map.of("hostIp",host,"username",settings.hosts.get(host)));
   }
  });
  try{workers.execute(()->submit(clusterId,configs,targets));}catch(RejectedExecutionException e){db.update("UPDATE deploy_tasks SET status='FAILED',detail='Deployment queue full' WHERE cluster_id=?",clusterId);}
  return clusterId;
 }
 private void submit(String clusterId,List<Map<String,Object>> configs,List<Map<String,String>> targets){
  try{
   Map<String,Object> app=remote.submit(clusterId,renderer.render(Map.of("nodes",configs)),targets);
   tx.executeWithoutResult(status->{
    db.update("UPDATE clusters SET application_id=? WHERE id=?",app.get("applicationId"),clusterId);
    db.update("UPDATE deploy_tasks SET status='WAITING_APPROVAL',phase='WAITING_APPROVAL',script_uuid=?,detail='已合并为一份部署申请，等待oversight人工审批' WHERE cluster_id=?",app.get("uuid"),clusterId);
   });
  }catch(Exception e){db.update("UPDATE deploy_tasks SET status='UNKNOWN',detail='申请提交结果不确定，请到oversight按集群ID核查，勿重复提交' WHERE cluster_id=?",clusterId);}
 }
 @Scheduled(fixedDelayString="${clustermgr.poll-delay-ms:5000}")
 public void refresh(){
  for(Map<String,Object> cluster:db.queryForList("SELECT DISTINCT c.id,c.application_id FROM clusters c JOIN deploy_tasks t ON c.id=t.cluster_id WHERE c.application_id IS NOT NULL AND t.status IN ('WAITING_APPROVAL','RUNNING')")){
   try{updateApplication(cluster.get("id").toString(),remote.application(cluster.get("application_id").toString()));}
   catch(Exception e){db.update("UPDATE deploy_tasks SET detail='审批结果查询暂时失败，稍后自动查询；未重新提交执行' WHERE cluster_id=? AND status IN ('WAITING_APPROVAL','RUNNING')",cluster.get("id"));}
  }
 }
 @SuppressWarnings("unchecked") private void updateApplication(String clusterId,Map<String,Object> app){
  String state=app.get("status").toString();
  if(Set.of("PENDING","APPROVED","RUNNING").contains(state)){
   db.update("UPDATE deploy_tasks SET status=?,detail=? WHERE cluster_id=? AND status IN ('WAITING_APPROVAL','RUNNING')",state.equals("PENDING")?"WAITING_APPROVAL":"RUNNING",state.equals("PENDING")?"等待人工审批":"审批通过，执行中",clusterId);return;
  }
  Map<String,Object> results=app.get("results") instanceof Map?(Map<String,Object>)app.get("results"):Collections.emptyMap();
  for(Map<String,Object> task:tasks(clusterId)){
   Object code=results.get(task.get("host_ip")+"@"+task.get("username"));String status,detail;
   if(state.equals("REJECTED")){status="REJECTED";detail="审批驳回，未执行："+Objects.toString(app.get("reason"),"");}
   else if(code!=null && code.toString().equals("1") && task.get("phase").equals("SUCCEEDED")){status="SUCCEEDED";detail="脚本退出0，ES集群验证通过";}
   else if(code!=null && code.toString().equals("0")){status="FAILED";detail="脚本返回0，请核查回传与远端日志；可能留有安装文件或进程";}
   else {status="UNKNOWN";detail="执行结果或最终回传不完整，请核查远端状态";}
   db.update("UPDATE deploy_tasks SET status=?,detail=?,finished_at=? WHERE id=?",status,detail.substring(0,Math.min(500,detail.length())),Instant.now().toString(),task.get("id"));
  }
 }
 public static String hash(String s){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder h=new StringBuilder();for(byte b:bytes)h.append(String.format("%02x",b&255));return h.toString();}catch(Exception e){throw new IllegalStateException(e);}}
 private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
 public List<Map<String,Object>> list(){List<Map<String,Object>> clusters=db.queryForList("SELECT * FROM clusters ORDER BY created_at DESC");for(Map<String,Object> c:clusters){List<Map<String,Object>> tasks=tasks(c.get("id").toString());c.put("tasks",tasks);c.put("status",status(tasks));c.put("approvalUrl",settings.oversight+(c.get("application_id")==null?"/":"/approvals/"+c.get("application_id")));}return clusters;}
 public List<Map<String,Object>> tasks(String id){return db.queryForList("SELECT id,cluster_id,host_ip,username,script_uuid,status,phase,last_seq,created_at,finished_at,detail FROM deploy_tasks WHERE cluster_id=? ORDER BY host_ip",id);}
 public List<Map<String,Object>> events(String id){return db.queryForList("SELECT e.* FROM deploy_events e JOIN deploy_tasks t ON t.id=e.task_id WHERE t.cluster_id=? ORDER BY e.created_at,e.seq",id);}
 private String status(List<Map<String,Object>> tasks){if(tasks.stream().anyMatch(t->Set.of("QUEUED","RUNNING").contains(t.get("status"))))return "DEPLOYING";if(tasks.stream().anyMatch(t->"WAITING_APPROVAL".equals(t.get("status"))))return "WAITING_APPROVAL";if(tasks.stream().allMatch(t->"SUCCEEDED".equals(t.get("status"))))return "SUCCEEDED";if(tasks.stream().anyMatch(t->"UNKNOWN".equals(t.get("status"))))return "UNKNOWN";if(tasks.stream().allMatch(t->"REJECTED".equals(t.get("status"))))return "REJECTED";return "FAILED";}
}
