package com.ycyh.clustermgr;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class V9RemoteSystemsTest {
  RemoteSystems remote;
  MockRestServiceServer server;

  @BeforeEach
  void init() {
    Settings settings =
        new Settings(
            "http://oversight", "http://warehouse", "http://callback", "", "127.0.0.1@es", true);
    remote =
        new RemoteSystems(
            settings, new ObjectMapper(), "operator", 2, "approver", "ycyh_es_install");
    ReflectionTestUtils.setField(remote, "rejectedAuditType", 2);
    server =
        MockRestServiceServer.createServer(
            (RestTemplate) ReflectionTestUtils.getField(remote, "http"));
    server
        .expect(requestTo("http://oversight/api/system/authority/getSecretToken?username=operator"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"code\":\"10000\",\"message\":\"login.success\",\"data\":{\"token\":\"fixture\",\"exipreTime\":\"20991231235959\"}}",
                MediaType.APPLICATION_JSON));
  }

  void response(String path, String data) {
    server
        .expect(requestTo("http://oversight/api/" + path))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("secretToken", "fixture"))
        .andRespond(
            withSuccess(
                "{\"code\":\"10000\",\"message\":null,\"data\":" + data + "}",
                MediaType.APPLICATION_JSON));
  }

  @Test
  void submitsOnlyDocumentedFieldsUsingExactLongIds() throws Exception {
    response(
        "scriptApi/myScript/getScriptDetail",
        "{\"scriptVersionDto\":{\"id\":1000000000000000097,\"srcScriptUuid\":\"fixture-uuid\",\"parameterValidationDtoList\":[{\"id\":1000000000000000098,\"paramName\":\"configBase64\",\"paramOrder\":1,\"paramType\":\"String\"}]}}");
    response(
        "scriptApi/groups/queryAgentInfoGroupRole",
        "{\"list\":[{\"sysmAgentInfoId\":1000000000000000099,\"agentIp\":\"127.0.0.1\",\"agentPort\":15000}]}");
    server
        .expect(requestTo("http://oversight/api/scriptApi/taskApply/scriptExecAuditing"))
        .andExpect(header("secretToken", "fixture"))
        .andExpect(jsonPath("$.scriptInfoVersionId").value(1000000000000000097L))
        .andExpect(jsonPath("$.params[0].paramDefaultValue").value("configuration"))
        .andExpect(jsonPath("$.chosedAgentUsers[0].sysmAgentInfoId").value(1000000000000000099L))
        .andExpect(jsonPath("$.execuser").value("es"))
        .andExpect(jsonPath("$.auditUserId").value(2))
        .andRespond(
            withSuccess(
                "{\"code\":\"10000\",\"message\":\"scriptExecAuditing.success\",\"data\":{\"taskId\":1000000000000000101,\"auditId\":1000000000000000102}}",
                MediaType.APPLICATION_JSON));
    Map<String, Object> result =
        remote.submit(
            "cluster", "configuration", List.of(Map.of("hostIp", "127.0.0.1", "username", "es")));
    assertEquals("1000000000000000101", result.get("applicationId"));
    assertEquals("1000000000000000102", result.get("auditId"));
    server.verify();
  }

  @Test
  void startsApprovedTaskWithBindingIdsAndMapsRuntimeState() {
    response(
        "scriptApi/taskExecute/listTaskReadyToExecute",
        "{\"list\":[{\"scriptTaskId\":1000000000000000101}]}");
    response(
        "scriptApi/info/getTaskBindAgentInfo", "{\"list\":[{\"taskIpsId\":1000000000000000103}]}");
    server
        .expect(requestTo("http://oversight/api/scriptApi/taskExecute/scriptTaskStart"))
        .andExpect(jsonPath("$.scriptTaskId").value(1000000000000000101L))
        .andExpect(jsonPath("$.taskIps[0]").value(1000000000000000103L))
        .andExpect(jsonPath("$.firstBatch").value(true))
        .andRespond(withSuccess("{\"code\":\"10000\",\"data\":\"\"}", MediaType.APPLICATION_JSON));
    response(
        "scriptApi/taskExecute/listRunningScriptTasks",
        "{\"list\":[{\"scriptTaskInstanceState\":20,\"scriptTaskInstanceId\":1000000000000000104}]}");
    response(
        "scriptApi/runtime/listTaskRuntime",
        "{\"list\":[{\"agentIp\":\"127.0.0.1\",\"execUser\":\"es\",\"state\":20}]}");
    Map<String, Object> app = remote.application("1000000000000000101");
    assertEquals("SUCCEEDED", app.get("status"));
    assertEquals(1, ((Map<?, ?>) app.get("results")).get("127.0.0.1@es"));
    server.verify();
  }

  @Test
  void configuredRejectionConventionDoesNotStartTask() {
    response("scriptApi/taskExecute/listTaskReadyToExecute", "{\"list\":[]}");
    response(
        "scriptApi/taskExecute/listRunningScriptTasks",
        "{\"list\":[{\"auditType\":2,\"scriptTaskInstanceId\":null}]}");
    assertEquals("REJECTED", remote.application("1000000000000000101").get("status"));
    server.verify();
  }
}
