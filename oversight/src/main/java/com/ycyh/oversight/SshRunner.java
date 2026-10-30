package com.ycyh.oversight;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class SshRunner {
 private final Set<String> hosts,users;
 private final String key,knownHosts,command;
 private final int port; private final long timeout;
 private final Semaphore slots=new Semaphore(8);
 public SshRunner(@Value("${oversight.allowed-hosts}") String hosts,@Value("${oversight.allowed-users}") String users,
  @Value("${oversight.private-key}") String key,@Value("${oversight.known-hosts}") String knownHosts,
  @Value("${oversight.ssh-command}") String command,@Value("${oversight.ssh-port}") int port,
  @Value("${oversight.timeout-seconds}") long timeout) {
  this.hosts=new HashSet<>(Arrays.asList(hosts.split(",")));this.users=new HashSet<>(Arrays.asList(users.split(",")));
  this.key=key;this.knownHosts=knownHosts;this.command=command;this.port=port;this.timeout=timeout;
 }
 public void validate(String host,String user) {
  if(host==null || !host.matches("[0-9a-fA-F:.]+") || !hosts.contains(host) || user==null || !user.matches("[a-z_][a-z0-9_-]{0,31}") || !users.contains(user))
   throw new IllegalArgumentException("Host/user not allowed");
 }
 public int run(String script,String host,String user) throws IOException,InterruptedException {
  validate(host,user);
  if(!slots.tryAcquire()) return 0;
  Path input=null; Process process=null;
  try {
   input=Files.createTempFile("oversight-", ".sh");
   Files.writeString(input,script,StandardCharsets.UTF_8);
   ProcessBuilder pb=new ProcessBuilder(command,"-T","-p",Integer.toString(port),"-i",key,
    "-o","BatchMode=yes","-o","IdentitiesOnly=yes","-o","StrictHostKeyChecking=yes",
    "-o","UserKnownHostsFile="+knownHosts,"-o","ConnectTimeout=15","-o","ServerAliveInterval=15",
    "-o","ServerAliveCountMax=3","--",user+"@"+host,"env YCYH_TARGET_IP="+host+" bash -s");
   pb.redirectInput(input.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
   process=pb.start();
   if(!process.waitFor(timeout,TimeUnit.SECONDS)) {process.destroyForcibly();return 0;}
   return process.exitValue()==0?1:0;
  } finally {
   if(process!=null && process.isAlive()) process.destroyForcibly();
   try {if(input!=null)Files.deleteIfExists(input);} finally {slots.release();}
  }
 }
}