package com.ycyh.oversight.v9;

import static com.ycyh.oversight.v9.V9Json.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class V9Identity {
  private final JdbcTemplate db;
  public final String operator, reviewer;
  private final long ttl;
  private final long operatorId, reviewerId;

  public V9Identity(
      JdbcTemplate db,
      @Value("${oversight.v9.operator:operator}") String operator,
      @Value("${oversight.admin-user}") String reviewer,
      @Value("${oversight.v9.token-ttl-seconds:7200}") long ttl,
      @Value("${oversight.v9.operator-id:1}") long operatorId,
      @Value("${oversight.v9.reviewer-id:2}") long reviewerId) {
    this.db = db;
    this.operator = operator;
    this.reviewer = reviewer;
    this.ttl = ttl;
    this.operatorId = operatorId;
    this.reviewerId = reviewerId;
    if (operator.equals(reviewer)
        || ttl < 1
        || operatorId < 1
        || reviewerId < 1
        || operatorId == reviewerId)
      throw new IllegalArgumentException("Distinct operator/reviewer required");
  }

  public long userId(String name) {
    if (name.equals(operator)) return operatorId;
    if (name.equals(reviewer)) return reviewerId;
    throw new IllegalArgumentException("user.not.found");
  }

  public String name(long id) {
    if (id == operatorId) return operator;
    if (id == reviewerId) return reviewer;
    throw new IllegalArgumentException("user.not.found");
  }

  public boolean admin(String name) {
    return reviewer.equals(name);
  }

  public ObjectNode user(String name) {
    long id = userId(name);
    ObjectNode n =
        object(
            "id",
            id,
            "loginName",
            name,
            "access",
            "it's a secret",
            "fullName",
            name,
            "locked",
            0,
            "adminPerm",
            admin(name) ? 5 : 0,
            "firstLogin",
            0,
            "type",
            0,
            "state",
            0,
            "creatorId",
            reviewerId,
            "creatorName",
            reviewer,
            "orgName",
            "YCYH",
            "postName",
            admin(name) ? "审批人" : "调用用户",
            "orgCode",
            "YCYH#",
            "level",
            1,
            "commUserLoginEnable",
            0);
    for (String k :
        "synUserId,sysmUserChatId,idnumber,email,telephone,pwUpdateTime,publicAccess,sysmCenterId,deleted,createTime,updatorId,updatorName,updateTime,orgId,postId,listOrgPost,isAutoLogin,token,userRoleRelId,confirmPassword,excludePassword,userGroupUserRelId,adaptorName,verificationCode"
            .split(",")) n.putNull(k);
    return n;
  }

  public ObjectNode issue(String username) {
    userId(username);
    byte[] bytes = new byte[48];
    new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    long expiry = System.currentTimeMillis() + ttl * 1000;
    db.update("DELETE FROM v9_tokens WHERE expires_at<?", System.currentTimeMillis());
    db.update(
        "INSERT INTO v9_tokens(token_hash,login_name,expires_at) VALUES(?,?,?)",
        hash(token),
        username,
        expiry);
    return ok(
        "login.success",
        object(
            "exipreTime",
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(Instant.ofEpochMilli(expiry).atZone(ZoneId.of("Asia/Shanghai"))),
            "user",
            user(username),
            "token",
            token));
  }

  public String authenticate(String token) {
    if (token == null || token.isEmpty()) throw new IllegalArgumentException("token.invalid");
    List<String> rows =
        db.queryForList(
            "SELECT login_name FROM v9_tokens WHERE token_hash=? AND expires_at>?",
            String.class,
            hash(token),
            System.currentTimeMillis());
    if (rows.isEmpty()) throw new IllegalArgumentException("token.invalid");
    userId(rows.get(0));
    return rows.get(0);
  }

  public static String hash(String value) {
    try {
      StringBuilder s = new StringBuilder();
      for (byte b :
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
        s.append(String.format("%02x", b & 255));
      return s.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
