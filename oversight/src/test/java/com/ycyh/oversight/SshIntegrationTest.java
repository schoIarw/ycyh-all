package com.ycyh.oversight;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="YCYH_SSH_TEST",matches="true")
class SshIntegrationTest {
 @Test void executesUsingRealOpenSshAndRejectsUnknownHostKey()throws Exception{
  String user=System.getenv("YCYH_SSH_USER"),key=System.getenv("YCYH_SSH_KEY"),known=System.getenv("YCYH_SSH_KNOWN_HOSTS");int port=Integer.parseInt(System.getenv("YCYH_SSH_PORT"));
  SshRunner runner=new SshRunner("127.0.0.1",user,key,known,"ssh",port,10);
  assertEquals(1,runner.run("test \"$YCYH_TARGET_IP\" = 127.0.0.1\nprintf 'real-ssh-ok'\n","127.0.0.1",user));
  assertEquals(0,runner.run("exit 42\n","127.0.0.1",user));
  Path empty=Files.createTempFile("unknown-host", ".txt");try{assertEquals(0,new SshRunner("127.0.0.1",user,key,empty.toString(),"ssh",port,10).run("exit 0\n","127.0.0.1",user));}finally{Files.deleteIfExists(empty);}
 }
}