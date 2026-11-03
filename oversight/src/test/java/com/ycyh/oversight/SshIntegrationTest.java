package com.ycyh.oversight;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "YCYH_SSH_TEST", matches = "true")
class SshIntegrationTest {
  @Test
  void executesUsingRealOpenSshAndRejectsUnknownHostKey() throws Exception {
    String user = System.getenv("YCYH_SSH_USER"),
        key = System.getenv("YCYH_SSH_KEY"),
        known = System.getenv("YCYH_SSH_KNOWN_HOSTS");
    int port = Integer.parseInt(System.getenv("YCYH_SSH_PORT"));
    SshRunner runner = new SshRunner("127.0.0.1", user, key, known, "ssh", port, 10);
    assertEquals(
        1,
        runner.run(
            "test \"$YCYH_TARGET_IP\" = 127.0.0.1\nprintf 'real-ssh-ok'\n", "127.0.0.1", user));
    assertEquals(0, runner.run("exit 42\n", "127.0.0.1", user));
    Path empty = Files.createTempFile("unknown-host", ".txt");
    try {
      assertEquals(
          0,
          new SshRunner("127.0.0.1", user, key, empty.toString(), "ssh", port, 10)
              .run("exit 0\n", "127.0.0.1", user));
    } finally {
      Files.deleteIfExists(empty);
    }
  }

  @Test
  void v9CapturesOutputPassesLiteralArgumentsAndStopsRemoteGroups() throws Exception {
    String user = System.getenv("YCYH_SSH_USER"),
        key = System.getenv("YCYH_SSH_KEY"),
        known = System.getenv("YCYH_SSH_KNOWN_HOSTS");
    int port = Integer.parseInt(System.getenv("YCYH_SSH_PORT"));
    SshRunner policy = new SshRunner("127.0.0.1", user, key, known, "ssh", port, 10);
    com.ycyh.oversight.v9.V9SshExecutor executor =
        new com.ycyh.oversight.v9.V9SshExecutor(policy, key, known, "ssh", port);
    Path marker = Files.createTempDirectory("v9-argument-").resolve("must-not-exist");
    String argument = "space ' $(touch " + marker + ") 中文";
    long id = System.currentTimeMillis() * 1000;
    com.ycyh.oversight.v9.V9SshExecutor.Result result =
        executor.execute(
            id,
            "printf '%s\\n' \"$1\"; printf 'error-line' >&2; printf '%s' \"$YCYH_TARGET_IP\"",
            "sh",
            "127.0.0.1",
            user,
            java.util.List.of(argument),
            java.util.List.of(),
            5);
    assertEquals(0, result.exitCode, result.stderr);
    assertTrue(result.stdout.contains(argument));
    assertEquals("127.0.0.1", result.lastLine);
    assertTrue(result.stderr.contains("error-line"));
    assertFalse(Files.exists(marker));
    Files.delete(marker.getParent());
    result =
        executor.execute(
            id + 1,
            "printf started; sleep 30",
            "sh",
            "127.0.0.1",
            user,
            java.util.List.of(),
            java.util.List.of(),
            1);
    assertTrue(result.timedOut);
    final long runtime = id + 2;
    java.util.concurrent.ExecutorService thread =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      java.util.concurrent.Future<com.ycyh.oversight.v9.V9SshExecutor.Result> job =
          thread.submit(
              () ->
                  executor.execute(
                      runtime,
                      "printf live-log; sleep 30",
                      "sh",
                      "127.0.0.1",
                      user,
                      java.util.List.of(),
                      java.util.List.of(),
                      0));
      long deadline = System.nanoTime() + 5_000_000_000L;
      while ((executor.live(runtime) == null || !executor.live(runtime).stdout.contains("live-log"))
          && System.nanoTime() < deadline) Thread.sleep(20);
      assertNotNull(executor.live(runtime));
      assertTrue(executor.live(runtime).stdout.contains("live-log"));
      executor.stop(runtime, "127.0.0.1", user);
      assertTrue(job.get(8, java.util.concurrent.TimeUnit.SECONDS).cancelled);
    } finally {
      thread.shutdownNow();
    }
  }
}
