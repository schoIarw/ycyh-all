package com.ycyh.oversight.v9;

import static com.ycyh.oversight.v9.V9Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ycyh.oversight.SshRunner;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Real SSH transport with bounded output, argument quoting and scoped process-group cancellation.
 */
@Service
public class V9SshExecutor {
  public static class Result {
    public int exitCode = -1;
    public boolean timedOut, cancelled;
    public String stdout = "", stderr = "", lastLine = "";

    public String log() {
      return "stdOut:\n" + stdout + "\nstdError:\n" + stderr + "\n";
    }
  }

  private static class Capture {
    final StringBuilder out = new StringBuilder(),
        err = new StringBuilder(),
        tail = new StringBuilder();
    boolean truncatedOut, truncatedErr;

    synchronized void append(boolean error, char[] chars, int size) {
      StringBuilder target = error ? err : out;
      int keep = Math.min(size, 1048576 - target.length());
      if (keep > 0) target.append(chars, 0, keep);
      if (keep < size) {
        if (error) truncatedErr = true;
        else truncatedOut = true;
      }
      if (!error) {
        tail.append(chars, 0, size);
        if (tail.length() > 65536) tail.delete(0, tail.length() - 65536);
      }
    }

    synchronized Result snapshot() {
      Result r = new Result();
      r.stdout = out.toString() + (truncatedOut ? "\n[output truncated]" : "");
      r.stderr = err.toString() + (truncatedErr ? "\n[output truncated]" : "");
      String s = tail.toString().stripTrailing();
      int i = s.lastIndexOf('\n');
      r.lastLine = s.substring(i + 1).strip();
      return r;
    }
  }

  private static class Running {
    Process process;
    Capture capture = new Capture();
  }

  private final SshRunner policy;
  private final String key, known, command;
  private final int port;
  private final ConcurrentMap<Long, Running> active = new ConcurrentHashMap<>();
  private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
  private final Object launchLock = new Object();

  public V9SshExecutor(
      SshRunner policy,
      @Value("${oversight.private-key}") String key,
      @Value("${oversight.known-hosts}") String known,
      @Value("${oversight.ssh-command}") String command,
      @Value("${oversight.ssh-port}") int port) {
    this.policy = policy;
    this.key = key;
    this.known = known;
    this.command = command;
    this.port = port;
  }

  private ProcessBuilder ssh(String host, String user, String remote) {
    policy.validate(host, user);
    return new ProcessBuilder(
        command,
        "-T",
        "-p",
        Integer.toString(port),
        "-i",
        key,
        "-o",
        "BatchMode=yes",
        "-o",
        "IdentitiesOnly=yes",
        "-o",
        "StrictHostKeyChecking=yes",
        "-o",
        "UserKnownHostsFile=" + known,
        "-o",
        "ConnectTimeout=15",
        "-o",
        "ServerAliveInterval=15",
        "-o",
        "ServerAliveCountMax=3",
        "--",
        user + "@" + host,
        remote);
  }

  public static String quote(String value) {
    if (value.indexOf(0) >= 0) throw new IllegalArgumentException("invalid.parameter");
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  public Result live(long runtime) {
    Running r = active.get(runtime);
    return r == null ? null : r.capture.snapshot();
  }

  public Result execute(
      long runtime,
      String script,
      String type,
      String host,
      String user,
      List<String> args,
      List<JsonNode> attachments,
      long seconds)
      throws Exception {
    policy.validate(host, user);
    Path source = Files.createTempFile("oversight-v9-", ".sh");
    Running running = new Running();
    Thread out = null, err = null;
    try {
      String wrapper = wrapper(runtime, script, type, args, attachments);
      Files.writeString(source, wrapper, StandardCharsets.UTF_8);
      synchronized (launchLock) {
        if (cancelled.contains(runtime)) {
          Result r = new Result();
          r.cancelled = true;
          return r;
        }
        running.process =
            ssh(host, user, "env YCYH_TARGET_IP=" + host + " bash -s")
                .redirectInput(source.toFile())
                .start();
        active.put(runtime, running);
      }
      out = reader(running.process.getInputStream(), running.capture, false);
      err = reader(running.process.getErrorStream(), running.capture, true);
      boolean finished;
      if (seconds <= 0) {
        running.process.waitFor();
        finished = true;
      } else finished = running.process.waitFor(seconds, TimeUnit.SECONDS);
      if (!finished) {
        killRemote(runtime, host, user);
        running.process.destroyForcibly();
        running.process.waitFor(3, TimeUnit.SECONDS);
      }
      out.join(3000);
      err.join(3000);
      Result result = running.capture.snapshot();
      result.timedOut = !finished;
      result.cancelled = cancelled.contains(runtime);
      result.exitCode = finished ? running.process.exitValue() : -1;
      return result;
    } catch (InterruptedException ex) {
      if (running.process != null) running.process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw ex;
    } finally {
      active.remove(runtime);
      if (running.process != null && running.process.isAlive()) running.process.destroyForcibly();
      Files.deleteIfExists(source);
      cancelled.remove(runtime);
    }
  }

  private Thread reader(InputStream in, Capture capture, boolean error) {
    Thread t =
        new Thread(
            () -> {
              try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                int n;
                while ((n = r.read(buffer)) >= 0) capture.append(error, buffer, n);
              } catch (IOException ignored) {
              }
            },
            "v9-output");
    t.setDaemon(true);
    t.start();
    return t;
  }

  public void stop(long runtime, String host, String user) throws Exception {
    Running r;
    synchronized (launchLock) {
      cancelled.add(runtime);
      r = active.get(runtime);
    }
    if (r == null) return;
    boolean killed = killRemote(runtime, host, user);
    if (r != null && r.process.isAlive()) {
      r.process.destroy();
      if (!r.process.waitFor(3, TimeUnit.SECONDS)) r.process.destroyForcibly();
    }
    if (!killed) throw new IOException("Remote stop not confirmed; inspect host process");
  }

  private boolean killRemote(long runtime, String host, String user) throws Exception {
    String remote =
        "b=\"$HOME/.ycyh-oversight\"; [ ! -L \"$b\" ] || exit 1; mkdir -p -- \"$b\"; (umask 077; :"
            + " > \"$b/"
            + runtime
            + ".cancel\"); d=\"$b/"
            + runtime
            + "\"; if [ -L \"$d\" ]; then exit 1; fi; if [ -f \"$d/pid\" ]; then read -r p <"
            + " \"$d/pid\"; case \"$p\" in ''|*[!0-9]*) exit 1;; esac; kill -TERM -- -\"$p\""
            + " 2>/dev/null || true; for i in $(seq 1 20);do kill -0 -- -\"$p\" 2>/dev/null || exit"
            + " 0;sleep .1;done;kill -KILL -- -\"$p\" 2>/dev/null || ! kill -0 -- -\"$p\""
            + " 2>/dev/null; else exit 0; fi";
    Process p =
        ssh(host, user, remote)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    p.getOutputStream().close();
    if (!p.waitFor(20, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      return false;
    }
    return p.exitValue() == 0;
  }

  private String wrapper(
      long id, String script, String type, List<String> args, List<JsonNode> attachments) {
    String interpreter;
    switch (type) {
      case "sh":
        interpreter = "bash";
        break;
      case "py":
        interpreter = "python3";
        break;
      case "ps1":
        interpreter = "pwsh -NoProfile -File";
        break;
      case "bat":
        interpreter = "cmd.exe /c";
        break;
      default:
        throw new IllegalArgumentException("invalid.scriptType");
    }
    StringBuilder b =
        new StringBuilder(
            "#!/usr/bin/env bash\n"
                + "set -eu\n"
                + "umask 077\n"
                + "base=\"$HOME/.ycyh-oversight\"\n"
                + "[ ! -L \"$base\" ] || exit 74\n"
                + "mkdir -p -- \"$base\"\n"
                + "work=\"$base/"
                + id
                + "\"\n"
                + "mkdir -m 700 -- \"$work\" || exit 75\n"
                + "child=''\n"
                + "cleanup(){ if [ -n \"$child\" ];then kill -TERM -- -\"$child\" 2>/dev/null ||"
                + " true;fi;rm -rf -- \"$work\"; rm -f -- \"$work.cancel\"; }\n"
                + "trap cleanup EXIT\n"
                + "trap 'exit 143' HUP INT TERM\n"
                + "[ ! -f \"$work.cancel\" ] || exit 143\n");
    b.append("printf '%s' ")
        .append(quote(Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8))))
        .append(" | base64 -d > \"$work/main.sh\"\n");
    for (JsonNode a : attachments) {
      V9Catalog.validateAttachment(a);
      if (text(a, "contents").isEmpty())
        throw new IllegalArgumentException("attachment.content.missing");
      b.append("printf '%s' ")
          .append(quote(text(a, "contents")))
          .append(" | base64 -d > \"$work/\"")
          .append(quote(text(a, "name")))
          .append('\n');
    }
    b.append("cd -- \"$work\"\nset +e\nsetsid ").append(interpreter).append(" ./main.sh");
    for (String arg : args) b.append(' ').append(quote(arg));
    b.append(
        " < /dev/null &\n"
            + "child=$!\n"
            + "printf '%s\\n"
            + "' \"$child\" > \"$work/pid\"\n"
            + "if [ -f \"$work.cancel\" ];then kill -TERM -- -\"$child\" 2>/dev/null || true;fi\n"
            + "wait \"$child\"\n"
            + "code=$?\n"
            + "child=''\n"
            + "exit \"$code\"\n");
    return b.toString();
  }
}
