#!/usr/bin/env python3
"""Optional real SSH transport test: OpenSSH client, userland Paramiko fixture.
Use when a container cannot run sshd's privilege separation. Requires paramiko.
The fixture binds loopback, accepts one ephemeral public key, and executes test commands.
"""
import os,socket,subprocess,tempfile,threading,pathlib
import paramiko
class Server(paramiko.ServerInterface):
    def __init__(self,key):self.key=key;self.ready=threading.Event();self.command=None
    def check_auth_publickey(self,user,key):return paramiko.AUTH_SUCCESSFUL if user=='fixture' and key==self.key else paramiko.AUTH_FAILED
    def get_allowed_auths(self,user):return 'publickey'
    def check_channel_request(self,kind,chanid):return paramiko.OPEN_SUCCEEDED if kind=='session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
    def check_channel_exec_request(self,channel,command):self.command=command.decode();self.ready.set();return True
with tempfile.TemporaryDirectory() as temp:
    p=pathlib.Path(temp);host=paramiko.RSAKey.generate(2048)
    subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-f',str(p/'client')],check=True)
    key=paramiko.Ed25519Key.from_private_key_file(str(p/'client'))
    sock=socket.socket();sock.bind(('127.0.0.1',0));sock.listen(8);port=sock.getsockname()[1];stop=threading.Event()
    (p/'known_hosts').write_text(f'[127.0.0.1]:{port} {host.get_name()} {host.get_base64()}\n')
    def handle(conn):
        transport=None
        try:
            transport=paramiko.Transport(conn);transport.add_server_key(host);server=Server(key);transport.start_server(server=server);channel=transport.accept(10)
            if channel is None or not server.ready.wait(10):return
            body=b''
            while True:
                chunk=channel.recv(65536)
                if not chunk:break
                body+=chunk
            result=subprocess.run(server.command,shell=True,input=body,capture_output=True,timeout=15)
            if result.stdout:channel.sendall(result.stdout)
            if result.stderr:channel.sendall_stderr(result.stderr)
            channel.send_exit_status(result.returncode);channel.shutdown_write();channel.close()
        except (EOFError,OSError,paramiko.SSHException):pass
        finally:
            if transport:transport.close()
            conn.close()
    def serve():
        while not stop.is_set():
            try:conn,_=sock.accept()
            except OSError:break
            threading.Thread(target=handle,args=(conn,),daemon=True).start()
    threading.Thread(target=serve,daemon=True).start()
    env=os.environ.copy();env.update(YCYH_SSH_TEST='true',YCYH_SSH_USER='fixture',YCYH_SSH_KEY=str(p/'client'),YCYH_SSH_KNOWN_HOSTS=str(p/'known_hosts'),YCYH_SSH_PORT=str(port))
    try:
        result=subprocess.run(['mvn','-B','-ntp','-pl','oversight','-am','-Dtest=SshIntegrationTest','-Dsurefire.failIfNoSpecifiedTests=false','test'],cwd=pathlib.Path(__file__).resolve().parents[1],env=env)
    finally:stop.set();sock.close()
    raise SystemExit(result.returncode)
