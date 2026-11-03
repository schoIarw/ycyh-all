package com.ycyh.oversight.v9;

import static com.ycyh.oversight.v9.V9Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

/** End-to-end public V9 HTTP -> persistent task -> real OpenSSH -> output/stop APIs. */
@EnabledIfEnvironmentVariable(named = "YCYH_SSH_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:v9-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "oversight.admin-password=test-approval-password",
      "oversight.allowed-hosts=127.0.0.1",
      "oversight.allowed-users=${YCYH_SSH_USER}",
      "oversight.private-key=${YCYH_SSH_KEY}",
      "oversight.known-hosts=${YCYH_SSH_KNOWN_HOSTS}",
      "oversight.ssh-port=${YCYH_SSH_PORT}"
    })
class V9HttpSshTest {
  @Autowired TestRestTemplate http;
  @Autowired V9Store store;
  @Autowired V9Catalog catalog;
  String operator, reviewer, user;
  long agent;

  @BeforeEach
  void init() {
    operator =
        http.getForObject("/api/system/authority/getSecretToken?username=operator", JsonNode.class)
            .path("data")
            .path("token")
            .asText();
    reviewer =
        http.getForObject("/api/system/authority/getSecretToken?username=approver", JsonNode.class)
            .path("data")
            .path("token")
            .asText();
    user = System.getenv("YCYH_SSH_USER");
    agent = store.find("AGENT", "127.0.0.1").get().path("id").asLong();
  }

  JsonNode post(String path, JsonNode body, String token) {
    HttpHeaders h = new HttpHeaders();
    h.set("secretToken", token);
    h.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> response =
        http.postForEntity("/api/" + path, new HttpEntity<>(body, h), JsonNode.class);
    assertEquals(200, response.getStatusCodeValue());
    return response.getBody();
  }

  JsonNode getOutput(long runtime) {
    HttpHeaders h = new HttpHeaders();
    h.set("secretToken", operator);
    return http.exchange(
            "/api/scriptApi/runtime/getOutPutMessage/?id=" + runtime,
            HttpMethod.GET,
            new HttpEntity<>(h),
            JsonNode.class)
        .getBody();
  }

  ObjectNode query(String key, long id) {
    return object("pageNum", 1, "pageSize", 100, "queryParam", object(key, id));
  }

  long apply(String content) {
    ObjectNode script =
        catalog.registerScript(
            object(
                "scriptName",
                "http_" + java.util.UUID.randomUUID(),
                "scriptNameZh",
                "HTTP SSH fixture",
                "scriptType",
                "sh",
                "execuser",
                user,
                "scriptVersionDto",
                object(
                    "level",
                    3,
                    "expectType",
                    2,
                    "expectLastline",
                    "0",
                    "scriptContentDto",
                    object("content", content),
                    "parameterValidationDtoList",
                    arr()
                        .add(
                            object(
                                "paramName",
                                "value",
                                "paramOrder",
                                1,
                                "paramType",
                                "String",
                                "paramDefaultValue",
                                "a b' $(false)")))));
    JsonNode applied =
        post(
            "scriptApi/taskApply/scriptExecAuditing",
            object(
                "scriptInfoVersionId",
                script.path("scriptVersionDto").get("id"),
                "auditUserId",
                2,
                "auditUser",
                "approver",
                "execuser",
                user,
                "chosedAgentUsers",
                arr().add(V9Catalog.agentRow(store.get("AGENT", agent))),
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
                    "HTTP SSH fixture",
                    "driveMode",
                    3,
                    "eachNum",
                    1,
                    "taskScheduler",
                    0,
                    "timeout",
                    10)),
            operator);
    assertEquals("10000", text(applied, "code"));
    return applied.path("data").path("taskId").asLong();
  }

  void approveAndStart(long task) {
    JsonNode t = store.get("TASK", task);
    assertEquals(
        "10000",
        text(
            post(
                "approvalApi/api/operation",
                object(
                    "taskId",
                    t.get("_auditId"),
                    "approvalState",
                    1,
                    "source",
                    0,
                    "approvalComment",
                    "HTTP SSH test"),
                reviewer),
            "code"));
    JsonNode bindings =
        post("scriptApi/info/getTaskBindAgentInfo", query("scriptTaskId", task), operator)
            .path("data")
            .path("list");
    ArrayNode ids = arr();
    bindings.forEach(b -> ids.add(b.get("taskIpsId")));
    assertEquals(
        "10000",
        text(
            post(
                "scriptApi/taskExecute/scriptTaskStart",
                object(
                    "scriptTaskId",
                    task,
                    "driveMode",
                    3,
                    "eachNum",
                    1,
                    "firstBatch",
                    true,
                    "taskIps",
                    ids),
                operator),
            "code"));
  }

  JsonNode waitForRuntime(long task, int state) throws Exception {
    long deadline = System.nanoTime() + 12_000_000_000L;
    JsonNode last = null;
    do {
      JsonNode details =
          post(
                  "scriptApi/taskExecute/listRunningScriptTasks",
                  query("scriptTaskId", task),
                  operator)
              .path("data")
              .path("list")
              .get(0);
      if (details.hasNonNull("scriptTaskInstanceId")) {
        JsonNode rows =
            post(
                    "scriptApi/runtime/listTaskRuntime",
                    query("taskInstanceId", details.path("scriptTaskInstanceId").asLong()),
                    operator)
                .path("data")
                .path("list");
        if (!rows.isEmpty()) {
          last = rows.get(0);
          if (last.path("state").asInt() == state) return last;
        }
      }
      Thread.sleep(30);
    } while (System.nanoTime() < deadline);
    org.junit.jupiter.api.Assertions.fail("Expected runtime state " + state + ", last=" + last);
    return null;
  }

  @Test
  void approveStartCompleteAndRetrieveRealOutput() throws Exception {
    long task = apply("printf 'real-task-%s\\n' \"$1\"; echo 0");
    assertEquals("AWAITING_APPROVAL", text(store.get("TASK", task), "_status"));
    approveAndStart(task);
    JsonNode runtime = waitForRuntime(task, 20);
    assertTrue(
        getOutput(runtime.path("id").asLong())
            .path("data")
            .asText()
            .contains("real-task-a b' $(false)"));
  }

  @Test
  void stopApiTerminatesActualSshRuntime() throws Exception {
    long task = apply("printf 'waiting-for-stop\\n'; sleep 30");
    approveAndStart(task);
    JsonNode runtime = waitForRuntime(task, 10);
    long id = runtime.path("id").asLong();
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!getOutput(id).path("data").asText().contains("waiting-for-stop")
        && System.nanoTime() < deadline) Thread.sleep(30);
    assertTrue(getOutput(id).path("data").asText().contains("waiting-for-stop"));
    assertEquals(
        "10000",
        text(
            post(
                "scriptApi/taskExecute/stopScriptInstanceShell",
                object("taskId", task, "runTimeIds", arr().add(id)),
                operator),
            "code"));
    assertEquals(60, waitForRuntime(task, 60).path("state").asInt());
  }
}
