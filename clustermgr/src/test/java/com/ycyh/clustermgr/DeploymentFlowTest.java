package com.ycyh.clustermgr;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:clustermgr;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","clustermgr.admin-password=admin-password-123","clustermgr.oversight-key=123456789012345678901234567890","clustermgr.allow-insecure-es=true","clustermgr.hosts=127.0.0.1@es,127.0.0.2@es","clustermgr.poll-delay-ms=3600000"})
@AutoConfigureMockMvc
class DeploymentFlowTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired DeploymentService service;
 @MockBean RemoteSystems remote;
 @BeforeEach void clear(){db.update("DELETE FROM deploy_events");db.update("DELETE FROM deploy_tasks");db.update("DELETE FROM clusters");reset(remote);}
 void task(String id,String token,String status){db.update("INSERT INTO deploy_tasks(id,cluster_id,host_ip,username,token_hash,status,phase,created_at) VALUES(?,?,'127.0.0.1','es',?,?,?,?)",id,"c1",DeploymentService.hash(token),status,"STARTED",java.time.Instant.now().toString());}
 @Test void callbacksAuthenticateDeduplicateAndProtectFinalPhase()throws Exception{
  task("t1","secret","RUNNING");String body="{\"seq\":1,\"phase\":\"SUCCEEDED\",\"message\":\"healthy\"}";
  mvc.perform(post("/api/callbacks/t1").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/callbacks/t1").header("Authorization","Bearer secret").contentType("application/json").content(body)).andExpect(status().isOk());
  mvc.perform(post("/api/callbacks/t1").header("Authorization","Bearer secret").contentType("application/json").content(body)).andExpect(status().isOk());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM deploy_events",Integer.class));
  mvc.perform(post("/api/callbacks/t1").header("Authorization","Bearer secret").contentType("application/json").content("{\"seq\":2,\"phase\":\"PROGRESS\",\"message\":\"late\"}")).andExpect(status().isConflict());
  assertEquals("SUCCEEDED",db.queryForObject("SELECT phase FROM deploy_tasks WHERE id='t1'",String.class));
 }
 @Test void loginAndCsrfAreRequiredAndUiRenders()throws Exception{
  mvc.perform(get("/")).andExpect(status().is3xxRedirection());mvc.perform(get("/login")).andExpect(status().isOk());mvc.perform(get("/").with(user("admin"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("添加集群")));
  mvc.perform(post("/api/clusters").with(user("admin")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
 }
 @Test void multiHostDeploymentSubmitsOneCombinedApplicationAndPollsWithoutResubmit()throws Exception{
  RemoteSystems.Software sw=new RemoteSystems.Software();sw.name="elasticsearch-8.17.0-linux-x86_64.tar.gz";sw.path=sw.name;sw.sha256="a".repeat(64);when(remote.packages()).thenReturn(List.of(sw));
  when(remote.submit(anyString(),anyString(),anyList())).thenReturn(Map.of("applicationId","application1","uuid","script1"));
  DeploymentService.Create c=new DeploymentService.Create();c.name="test-cluster";c.packagePath=sw.path;c.hostIps=List.of("127.0.0.1","127.0.0.2");String cluster=service.create(c);
  verify(remote,timeout(3000)).submit(eq(cluster),contains("framework_main"),argThat(t->t.size()==2));
  long deadline=System.nanoTime()+3_000_000_000L;while(!"application1".equals(db.queryForObject("SELECT application_id FROM clusters WHERE id=?",String.class,cluster))&&System.nanoTime()<deadline)Thread.sleep(20);
  when(remote.application("application1")).thenReturn(Map.of("status","PENDING"));service.refresh();assertTrue(service.tasks(cluster).stream().allMatch(t->t.get("status").equals("WAITING_APPROVAL")));
  when(remote.application("application1")).thenReturn(Map.of("status","RUNNING"));service.refresh();
  db.update("UPDATE deploy_tasks SET phase='SUCCEEDED' WHERE cluster_id=?",cluster);
  when(remote.application("application1")).thenReturn(Map.of("status","SUCCEEDED","results",Map.of("127.0.0.1@es",1,"127.0.0.2@es",1)));service.refresh();
  assertTrue(service.tasks(cluster).stream().allMatch(t->t.get("status").equals("SUCCEEDED")));verify(remote,times(1)).submit(anyString(),anyString(),anyList());
 }
 @Test void rejectsUnknownHostBeforeCallingOversight(){DeploymentService.Create c=new DeploymentService.Create();c.name="valid";c.packagePath="es.tar.gz";c.hostIps=List.of("10.0.0.99");assertThrows(Exception.class,()->service.create(c));verifyNoInteractions(remote);}
 @Test void configuredPasswordAuthenticatesThroughRealLoginFilter()throws Exception{
  mvc.perform(post("/login").param("username","admin").param("password","admin-password-123").with(csrf())).andExpect(status().is3xxRedirection()).andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated().withUsername("admin"));
 }

}
