package com.ycyh.oversight;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class SshRunnerTest {
 @TempDir Path dir;
 SshRunner runner(String command,long timeout){return new SshRunner("127.0.0.1","es","/key","/known_hosts",command,22,timeout);}
 @Test void blocksHostAndUserInjection(){SshRunner r=runner("ssh",1);assertThrows(IllegalArgumentException.class,()->r.validate("127.0.0.1;touch /tmp/x","es"));assertThrows(IllegalArgumentException.class,()->r.validate("127.0.0.2","es"));assertThrows(IllegalArgumentException.class,()->r.validate("127.0.0.1","root"));assertThrows(IllegalArgumentException.class,()->r.validate("127.0.0.1","-oProxyCommand=x"));}
 @Test void mapsExitStatusAndTimesOut()throws Exception{
  Path executable=dir.resolve("ssh");Files.writeString(executable,"#!/bin/sh\ncat >/dev/null\nexit 0\n");executable.toFile().setExecutable(true);assertEquals(1,runner(executable.toString(),2).run("echo ok","127.0.0.1","es"));
  Files.writeString(executable,"#!/bin/sh\nexit 42\n");assertEquals(0,runner(executable.toString(),2).run("echo ok","127.0.0.1","es"));
  Files.writeString(executable,"#!/bin/sh\nexec sleep 5\n");long start=System.nanoTime();assertEquals(0,runner(executable.toString(),1).run("echo ok","127.0.0.1","es"));assertTrue((System.nanoTime()-start)<4_000_000_000L);
 }
}
