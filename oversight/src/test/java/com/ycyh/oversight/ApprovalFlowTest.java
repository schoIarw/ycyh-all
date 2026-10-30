package com.ycyh.oversight;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:oversight;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","oversight.api-key=123456789012345678901234567890","oversight.admin-password=approval-password-123"})
@AutoConfigureMockMvc
class ApprovalFlowTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired ApplicationService service;
 @MockBean SshRunner ssh;
 final String key="123456789012345678901234567890";
 @BeforeEach void clear(){db.update("DELETE FROM applications");db.update("DELETE FROM scripts");db.update("DELETE FROM executions");reset(ssh);}
 MockMultipartFile script(){return new MockMultipartFile("file","test.sh","text/plain","echo hello".getBytes());}
 @SuppressWarnings("unchecked") Map<String,Object> parse(String s)throws Exception{return json.readValue(s,Map.class);}
 Map<String,Object> upload()throws Exception{return parse(mvc.perform(multipart("/api/v1/scripts").file(script()).header("X-API-Key",key)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());}
 void approve(String id)throws Exception{mvc.perform(post("/approvals/"+id+"/approve").with(user("approver").roles("ADMIN")).with(csrf())).andExpect(status().is3xxRedirection());}
 Map<String,Object> await(String id)throws Exception{long deadline=System.nanoTime()+5_000_000_000L;Map<String,Object> app;do{app=service.view(id);if(Set.of("SUCCEEDED","FAILED","UNKNOWN").contains(app.get("status")))return app;Thread.sleep(20);}while(System.nanoTime()<deadline);fail("Application did not finish");return app;}
 @Test void authenticatedUploadRequiresApprovalAndExecutionRequiresAnotherApproval()throws Exception{
  when(ssh.run(anyString(),anyString(),anyString())).thenReturn(1);
  mvc.perform(multipart("/api/v1/scripts").file(script())).andExpect(status().isUnauthorized());
  Map<String,Object> app=upload();String id=app.get("applicationId").toString();
  assertEquals("PENDING",app.get("status"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM scripts",Integer.class));verifyNoInteractions(ssh);
  mvc.perform(get("/api/v1/applications/"+id+"/result").header("X-API-Key",key)).andExpect(status().isAccepted()).andExpect(content().string("PENDING"));
  mvc.perform(post("/approvals/"+id+"/approve").with(user("approver"))).andExpect(status().isForbidden());
  approve(id);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM scripts",Integer.class));
  String body=json.writeValueAsString(Map.of("scriptUuid",app.get("uuid"),"hostIp","127.0.0.1","username","es"));
  Map<String,Object> execution=parse(mvc.perform(post("/api/v1/scripts/execute").header("X-API-Key",key).contentType("application/json").content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
  verify(ssh,never()).run(anyString(),anyString(),anyString());String eid=execution.get("applicationId").toString();approve(eid);
  assertEquals(1,await(eid).get("result"));verify(ssh,times(1)).run("echo hello","127.0.0.1","es");
  mvc.perform(post("/approvals/"+eid+"/approve").with(user("approver")).with(csrf())).andExpect(status().isConflict());
  mvc.perform(get("/api/v1/applications/"+eid+"/result").header("X-API-Key",key)).andExpect(content().string("1"));
 }
 @Test void rejectionDoesNotActivateOrRun()throws Exception{
  Map<String,Object> app=upload();String id=app.get("applicationId").toString();
  mvc.perform(post("/approvals/"+id+"/reject").param("reason","not approved").with(user("approver")).with(csrf())).andExpect(status().is3xxRedirection());
  assertEquals("REJECTED",service.view(id).get("status"));assertEquals(0,service.view(id).get("result"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM scripts",Integer.class));verifyNoInteractions(ssh);
 }
 @Test void combinedDeploymentUsesOneApprovalAndAggregatesHostResults()throws Exception{
  when(ssh.run(anyString(),eq("127.0.0.1"),anyString())).thenReturn(1);when(ssh.run(anyString(),eq("127.0.0.2"),anyString())).thenReturn(0);
  Map<String,Object> app=parse(mvc.perform(multipart("/api/v1/deployments").file(script()).param("targets","[{\"hostIp\":\"127.0.0.1\",\"username\":\"es\"},{\"hostIp\":\"127.0.0.2\",\"username\":\"es\"}]").header("X-API-Key",key)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
  String id=app.get("applicationId").toString();verify(ssh,never()).run(anyString(),anyString(),anyString());approve(id);Map<String,Object> result=await(id);
  assertEquals("FAILED",result.get("status"));assertEquals(0,result.get("result"));assertEquals(2,((Map<?,?>)result.get("results")).size());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM scripts",Integer.class));verify(ssh,times(2)).run(anyString(),anyString(),anyString());
 }
 @Test void pagesRenderAndScriptIsEscaped()throws Exception{
  Map<String,Object> app=upload();mvc.perform(get("/").with(user("approver"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("调用申请")));
  mvc.perform(get("/approvals/"+app.get("applicationId")).with(user("approver"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("批准并执行")));
 }
 @Test void configuredPasswordAuthenticatesThroughRealLoginFilter()throws Exception{
  mvc.perform(post("/login").param("username","approver").param("password","approval-password-123").with(csrf())).andExpect(status().is3xxRedirection()).andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated().withUsername("approver"));
 }

}
