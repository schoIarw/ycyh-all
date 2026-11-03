package com.ycyh.oversight.v9;

import static com.ycyh.oversight.v9.V9Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class V9AuthorityController {
  private final V9Identity identity;
  private final V9Tasks tasks;

  public V9AuthorityController(V9Identity identity, V9Tasks tasks) {
    this.identity = identity;
    this.tasks = tasks;
  }

  @GetMapping({"/api/system/authority/getSecretToken", "/system/authority/getSecretToken"})
  public ObjectNode token(@RequestParam String username) {
    return identity.issue(username);
  }

  @PostMapping({"/api/approvalApi/api/operation", "/approvalApi/api/operation"})
  public ObjectNode approval(@RequestBody JsonNode req, HttpServletRequest http) {
    tasks.approve(req, (String) http.getAttribute("v9User"));
    return ok(null, null);
  }

  @GetMapping("/taskExecute/getRealTimeOutPutMessage")
  public ObjectNode realtime(@RequestParam long id, HttpServletRequest http) {
    return ok(
        "get.out.put.message.success", tasks.output(id, (String) http.getAttribute("v9User")));
  }
}
