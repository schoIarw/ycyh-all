package com.ycyh.oversight.v9;

import static com.ycyh.oversight.v9.V9Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:v9;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "oversight.admin-password=approval-password-123",
      "oversight.allowed-hosts=127.0.0.1,127.0.0.2",
      "oversight.allowed-users=es",
      "oversight.v9.scheduler-delay-ms=3600000"
    })
@AutoConfigureMockMvc
class V9ProtocolTest {
  @Autowired MockMvc mvc;
  @Autowired V9Store store;
  @Autowired V9Catalog catalog;
  @Autowired V9Tasks tasks;
  @Autowired V9Identity identity;
  @Autowired JdbcTemplate db;
  @MockBean V9SshExecutor ssh;
  String operator, reviewer;
  long agent, version, template;

  @BeforeEach
  void before() throws Exception {
    for (String kind : List.of("TASK", "RUNTIME", "INSTANCE")) {
      db.update("DELETE FROM v9_chunks WHERE kind=?", kind);
      db.update("DELETE FROM v9_objects WHERE kind=?", kind);
    }
    reset(ssh);
    when(ssh.execute(
            anyLong(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            anyList(),
            anyLong()))
        .thenAnswer(
            i -> {
              V9SshExecutor.Result r = new V9SshExecutor.Result();
              r.exitCode = 0;
              r.stdout = "fixture output\n0\n";
              r.lastLine = "0";
              return r;
            });
    operator = token("operator");
    reviewer = token("approver");
    agent = store.find("AGENT", "127.0.0.1").get().path("sysmAgentInfoId").asLong();
    template = store.all("TEMPLATE").get(0).path("id").asLong();
    ObjectNode s =
        object(
            "scriptName",
            "fixture_" + UUID.randomUUID(),
            "scriptNameZh",
            "fixture",
            "scriptType",
            "sh",
            "execuser",
            "es",
            "scriptVersionDto",
            object(
                "level",
                3,
                "expectType",
                2,
                "expectLastline",
                "0",
                "scriptContentDto",
                object("content", "printf 'fixture output\\n0\\n'"),
                "parameterValidationDtoList",
                arr()
                    .add(
                        object(
                            "paramName",
                            "value",
                            "paramType",
                            "String",
                            "paramOrder",
                            1,
                            "paramDefaultValue",
                            "default"))));
    version = catalog.registerScript(s).path("scriptVersionDto").path("id").asLong();
  }

  String token(String name) throws Exception {
    return parse(
            mvc.perform(get("/api/system/authority/getSecretToken").param("username", name))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data")
        .path("token")
        .asText();
  }

  JsonNode post(String path, JsonNode body, String token) throws Exception {
    return parse(
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .header("secretToken", token)
                    .contentType("application/json")
                    .content(body.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  ObjectNode request() {
    return object(
        "scriptInfoVersionId",
        version,
        "auditUserId",
        2,
        "auditUser",
        "approver",
        "execuser",
        "es",
        "chosedAgentUsers",
        arr().add(V9Catalog.agentRow(store.get("AGENT", agent))),
        "chosedResGroups",
        arr(),
        "resGroupFlag",
        false,
        "params",
        arr(),
        "scriptTempAttachments",
        arr(),
        "startType",
        0,
        "taskInfo",
        object(
            "taskName",
            "fixture task",
            "driveMode",
            3,
            "eachNum",
            2,
            "taskScheduler",
            0,
            "timeout",
            5));
  }

  ObjectNode query(String key, long id) {
    return object("pageNum", 1, "pageSize", 200, "queryParam", object(key, id));
  }

  JsonNode apply(ObjectNode req) throws Exception {
    JsonNode r = post("/api/scriptApi/taskApply/scriptExecAuditing", req, operator);
    assertEquals("10000", text(r, "code"), r.toString());
    return r.get("data");
  }

  JsonNode approve(JsonNode applied, int state, String who) throws Exception {
    return post(
        "/api/approvalApi/api/operation",
        object(
            "taskId",
            applied.get("auditId"),
            "approvalState",
            state,
            "approvalComment",
            state == 2 ? "rejected" : "approved",
            "source",
            0),
        who);
  }

  JsonNode start(long task) throws Exception {
    ArrayNode ids = tasks.bindingIds(store.get("TASK", task));
    return post(
        "/api/scriptApi/taskExecute/scriptTaskStart",
        object(
            "scriptTaskId", task, "taskIps", ids, "driveMode", 3, "eachNum", 2, "firstBatch", true),
        operator);
  }

  void await(long id) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (store.get("TASK", id).path("_active").asBoolean() && System.nanoTime() < deadline)
      Thread.sleep(20);
    assertFalse(store.get("TASK", id).path("_active").asBoolean());
  }

  @Test
  void tokenHeaderAliasesAndLegacyDisabled() throws Exception {
    JsonNode login =
        parse(
            mvc.perform(get("/system/authority/getSecretToken").param("username", "operator"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertEquals("10000", text(login, "code"));
    assertTrue(login.path("data").has("exipreTime"));
    assertFalse(login.path("data").has("expireTime"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/scriptApi/taskApply/listTaskApply")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("10602"));
    assertEquals(
        "10000",
        text(
            post(
                "/scriptApi/taskApply/listTaskApply/",
                object("pageNum", "1", "pageSize", "10", "queryParam", obj()),
                operator),
            "code"));
    mvc.perform(get("/api/v1/applications/none")).andExpect(status().isNotFound());
  }

  @Test
  void riskRequiresCorrectReviewerThenSeparateStartAndBindingIds() throws Exception {
    JsonNode applied = apply(request());
    long id = applied.path("taskId").asLong();
    assertTrue(applied.path("taskId").isIntegralNumber());
    assertTrue(id > 9007199254740991L);
    assertNotEquals(id, applied.path("auditId").asLong());
    assertEquals("10602", text(start(id), "code"));
    verify(ssh, never())
        .execute(
            anyLong(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            anyList(),
            anyLong());
    assertEquals("10602", text(approve(applied, 1, operator), "code"));
    assertEquals("10000", text(approve(applied, 1, reviewer), "code"));
    assertEquals("READY", text(store.get("TASK", id), "_status"));
    verify(ssh, never())
        .execute(
            anyLong(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            anyList(),
            anyLong());
    assertEquals(
        "10602",
        text(
            post(
                "/api/scriptApi/taskExecute/scriptTaskStart",
                object(
                    "scriptTaskId",
                    id,
                    "taskIps",
                    arr().add(id),
                    "driveMode",
                    1,
                    "eachNum",
                    1,
                    "firstBatch",
                    true),
                operator),
            "code"));
    assertEquals("10000", text(start(id), "code"));
    await(id);
    assertEquals(20, store.get("TASK", id).path("_instanceState").asInt());
    assertEquals("10602", text(start(id), "code"));
    assertEquals("10602", text(approve(applied, 1, reviewer), "code"));
    verify(ssh, times(1))
        .execute(
            anyLong(),
            anyString(),
            eq("sh"),
            eq("127.0.0.1"),
            eq("es"),
            anyList(),
            anyList(),
            eq(5L));
  }

  @Test
  void whitelistHasNullAuditAndAutoExecutesParametersSafely() throws Exception {
    store.edit("SCRIPT", version, s -> s.with("scriptVersionDto").put("level", 0));
    ObjectNode req = request();
    req.remove("auditUser");
    req.remove("auditUserId");
    String argument = "a b'; $(touch must-not-run) 中文";
    req.set(
        "params",
        arr().add(object("paramOrder", 1, "paramDefaultValue", argument, "paramType", "String")));
    JsonNode applied = apply(req);
    assertTrue(applied.get("auditId").isNull());
    long id = applied.path("taskId").asLong();
    await(id);
    assertEquals(20, store.get("TASK", id).path("_instanceState").asInt());
    verify(ssh)
        .execute(
            anyLong(),
            anyString(),
            eq("sh"),
            anyString(),
            eq("es"),
            eq(List.of(argument)),
            anyList(),
            eq(5L));
  }

  @Test
  void rejectionDoesNotExecuteAndSnapshotCannotBeChangedByRegistryUpdates() throws Exception {
    JsonNode a = apply(request());
    String original = store.get("TASK", a.path("taskId").asLong()).path("_script").toString();
    store.edit(
        "SCRIPT",
        version,
        s -> s.with("scriptVersionDto").with("scriptContentDto").put("content", "exit 77"));
    assertEquals(original, store.get("TASK", a.path("taskId").asLong()).path("_script").toString());
    assertEquals(
        "10602",
        text(
            post(
                "/api/approvalApi/api/operation",
                object("taskId", a.get("auditId"), "approvalState", 2, "source", 0),
                reviewer),
            "code"));
    assertEquals("10000", text(approve(a, 2, reviewer), "code"));
    assertEquals("10602", text(start(a.path("taskId").asLong()), "code"));
    verifyNoInteractions(ssh);
  }

  @Test
  void resourceGroupsTemplatesAndVariableParameterStart() throws Exception {
    ObjectNode req = request();
    req.set("chosedAgentUsers", arr());
    req.put("resGroupFlag", true);
    req.set(
        "chosedResGroups",
        arr().add(object("sysmComputerGroupId", store.all("GROUP").get(0).path("id"))));
    JsonNode applied = apply(req);
    assertEquals(2, store.get("TASK", applied.path("taskId").asLong()).path("_bindings").size());
    assertEquals("10000", text(approve(applied, 2, reviewer), "code"));
    ObjectNode temp =
        object(
            "taskName",
            "fixture common",
            "scriptInfoVersionId",
            version,
            "chosedAgentUsers",
            request().get("chosedAgentUsers"),
            "execuser",
            "es",
            "taskInfo",
            request().get("taskInfo"));
    long tid = catalog.registerTemplate(temp).path("id").asLong();
    JsonNode response =
        post(
            "/api/scriptApi/taskTemplate/startCommonTask",
            object(
                "commonTaskId",
                tid,
                "auditUserId",
                2,
                "params",
                arr()
                    .add(
                        object(
                            "paramType",
                            "String",
                            "paramOrder",
                            1,
                            "paramDefaultValue",
                            "changed"))),
            operator);
    assertEquals("10000", text(response, "code"));
    assertTrue(response.path("data").path("taskId").isIntegralNumber());
    assertTrue(response.path("data").path("auditId").isIntegralNumber());
  }

  @Test
  void timingAndQuartzSevenFieldCronAreHonored() throws Exception {
    store.edit("SCRIPT", version, s -> s.with("scriptVersionDto").put("level", 0));
    ObjectNode req = request();
    ObjectNode info = (ObjectNode) req.get("taskInfo");
    info.put("taskScheduler", 2);
    info.put("taskTime", System.currentTimeMillis() + 100000);
    JsonNode a = apply(req);
    long id = a.path("taskId").asLong();
    assertEquals("SCHEDULED", text(store.get("TASK", id), "_status"));
    verifyNoInteractions(ssh);
    store.edit("TASK", id, t -> t.put("_nextFire", System.currentTimeMillis() - 1));
    tasks.tick();
    await(id);
    assertEquals(20, store.get("TASK", id).path("_instanceState").asInt());
    info.put("taskScheduler", 1);
    info.put("taskCron", "0 0 0 * * ? *");
    a = apply(req);
    assertEquals("SCHEDULED", text(store.get("TASK", a.path("taskId").asLong()), "_status"));
  }

  @Test
  void outputFailureExpectationTimeoutAndTerminationStates() throws Exception {
    V9SshExecutor.Result failed = new V9SshExecutor.Result();
    failed.exitCode = 12;
    failed.stdout = "not-ok";
    failed.stderr = "fixture-error";
    when(ssh.execute(
            anyLong(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            anyList(),
            anyLong()))
        .thenReturn(failed);
    JsonNode a = apply(request());
    approve(a, 1, reviewer);
    long id = a.path("taskId").asLong();
    start(id);
    await(id);
    assertEquals(30, store.get("TASK", id).path("_instanceState").asInt());
    long instance = store.get("TASK", id).path("_instanceId").asLong();
    JsonNode rt =
        post("/api/scriptApi/runtime/listTaskRuntime", query("taskInstanceId", instance), operator)
            .path("data")
            .path("list")
            .get(0);
    JsonNode out =
        parse(
            mvc.perform(
                    get("/api/scriptApi/runtime/getOutPutMessage/")
                        .param("id", rt.get("id").asText())
                        .header("secretToken", operator))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertTrue(out.path("data").asText().contains("fixture-error"));
    assertNotEquals(instance, rt.path("id").asLong());
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(ssh.execute(
            anyLong(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            anyList(),
            anyLong()))
        .thenAnswer(
            i -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return failed;
            });
    a = apply(request());
    approve(a, 1, reviewer);
    long stopTask = a.path("taskId").asLong();
    start(stopTask);
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    long run =
        store.all("RUNTIME").stream()
            .filter(r -> r.path("scriptTaskId").asLong() == stopTask)
            .findFirst()
            .get()
            .path("id")
            .asLong();
    assertEquals(
        "10000",
        text(
            post(
                "/api/scriptApi/taskExecute/stopScriptInstanceShell",
                object("taskId", stopTask, "runTimeIds", arr().add(run)),
                operator),
            "code"));
    release.countDown();
    await(stopTask);
    assertEquals(60, store.get("RUNTIME", run).path("state").asInt());
    verify(ssh).stop(run, "127.0.0.1", "es");
  }

  @Test
  void documentDerivedResponseShapesAndEveryEndpointAreCovered() throws Exception {
    JsonNode a = apply(request());
    approve(a, 1, reviewer);
    long task = a.path("taskId").asLong();
    start(task);
    await(task);
    long instance = store.get("TASK", task).path("_instanceId").asLong(),
        runtime = store.all("RUNTIME").get(0).path("id").asLong();
    JsonNode contract = JSON.readTree(new ClassPathResource("v9-contracts.json").getInputStream());
    for (JsonNode endpoint : contract.path("interfaces")) {
      String path = endpoint.path("path").asText();
      JsonNode response;
      if (endpoint.path("method").asText().equals("GET")) {
        String query = path.contains("getSecretToken") ? "username=operator" : "id=" + runtime;
        response =
            parse(
                mvc.perform(get(path + "?" + query).header("secretToken", operator))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
      } else if (path.endsWith("scriptExecAuditing")) response = post(path, request(), operator);
      else if (path.endsWith("operation")) {
        JsonNode pending = apply(request());
        response = approve(pending, 1, reviewer);
      } else if (path.endsWith("scriptTaskStart")) {
        JsonNode pending = apply(request());
        approve(pending, 1, reviewer);
        response = start(pending.path("taskId").asLong());
        await(pending.path("taskId").asLong());
      } else if (path.endsWith("execAuditTemplateTask"))
        response =
            post(
                path,
                object(
                    "scriptInfoVersionId",
                    store.get("TEMPLATE", template).get("scriptInfoVersionId"),
                    "taskInfo",
                    object("id", template, "taskName", "common test"),
                    "auditUserId",
                    2,
                    "execuser",
                    "es",
                    "params",
                    arr(),
                    "resGroupFlag",
                    false,
                    "chosedAgentUsers",
                    request().get("chosedAgentUsers"),
                    "scriptTempAttachments",
                    arr()),
                operator);
      else if (path.endsWith("startCommonTask"))
        response =
            post(
                path,
                object("commonTaskId", template, "auditUserId", 2, "params", arr()),
                operator);
      else if (path.endsWith("getScriptTemplateDetail"))
        response =
            post(
                path,
                object(
                    "taskId",
                    template,
                    "srcScriptUuid",
                    catalog
                        .script(
                            store.get("TEMPLATE", template).path("scriptInfoVersionId").asLong())
                        .path("scriptVersionDto")
                        .get("srcScriptUuid")),
                operator);
      else if (path.endsWith("getScriptDetail"))
        response =
            post(
                path,
                object(
                    "srcScriptUuid",
                    catalog.script(version).path("scriptVersionDto").get("srcScriptUuid")),
                operator);
      else if (path.endsWith("stopScriptInstanceShell"))
        response = post(path, object("taskId", task, "runTimeIds", arr().add(runtime)), operator);
      else if (path.endsWith("queryTemplateGroupInfo") || path.endsWith("queryTemplateAgentInfo"))
        response = post(path, object("scriptTaskId", template), operator);
      else if (path.endsWith("listTaskRuntime"))
        response = post(path, query("taskInstanceId", instance), operator);
      else if (path.endsWith("selectCategoryListDFS")) response = post(path, obj(), operator);
      else if (path.endsWith("listTaskReadyToExecute")) {
        JsonNode pending = apply(request());
        approve(pending, 1, reviewer);
        response = post(path, query("scriptTaskId", pending.path("taskId").asLong()), operator);
      } else
        response =
            post(
                path,
                path.endsWith("listRunningScriptTasks") || path.endsWith("getTaskBindAgentInfo")
                    ? query("scriptTaskId", task)
                    : object("pageNum", 1, "pageSize", 200, "queryParam", obj()),
                operator);
      assertEquals("10000", text(response, "code"), path + " " + response);
      shape(endpoint.get("responseShape"), response, path);
    }
  }

  void shape(JsonNode schema, JsonNode actual, String path) {
    if (schema.isObject()) {
      assertTrue(actual.isObject(), path);
      schema
          .fields()
          .forEachRemaining(
              e -> {
                assertTrue(actual.has(e.getKey()), path + " missing " + e.getKey());
                shape(e.getValue(), actual.get(e.getKey()), path + "." + e.getKey());
              });
    } else if (schema.isArray()) {
      assertTrue(actual.isArray(), path);
      if (!schema.isEmpty() && !actual.isEmpty()) shape(schema.get(0), actual.get(0), path + "[]");
    } else if (!actual.isNull() && !schema.asText().equals("null")) {
      switch (schema.asText()) {
        case "string":
          assertTrue(actual.isTextual(), path);
          break;
        case "integer":
          assertTrue(actual.isIntegralNumber(), path);
          break;
        case "boolean":
          assertTrue(actual.isBoolean(), path);
          break;
        default:
          break;
      }
    }
  }

  @Test
  void nullTimeoutAndOutOfRangeIdentifiersFollowTheContract() throws Exception {
    ObjectNode request = request();
    ((ObjectNode) request.get("taskInfo")).putNull("timeout");
    JsonNode applied = apply(request);
    assertEquals(-1, store.get("TASK", applied.path("taskId").asLong()).path("timeout").asLong());
    assertEquals("10000", text(approve(applied, 2, reviewer), "code"));
    request.set(
        "scriptInfoVersionId", JSON.valueToTree(new java.math.BigInteger("9223372036854775808")));
    assertEquals(
        "10602",
        text(post("/api/scriptApi/taskApply/scriptExecAuditing", request, operator), "code"));
    verifyNoInteractions(ssh);
  }

  @Test
  void adminPagesRenderAndRegistrationRequiresSessionCsrf() throws Exception {
    mvc.perform(get("/").with(user("approver"))).andExpect(status().isOk());
    mvc.perform(get("/catalog").with(user("approver"))).andExpect(status().isOk());
    JsonNode applied = apply(request());
    mvc.perform(get("/approvals/" + applied.path("taskId").asText()).with(user("approver")))
        .andExpect(status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/catalog/import")
                .with(user("approver")))
        .andExpect(status().isForbidden());
  }
}
