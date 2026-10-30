#!/usr/bin/env python3
"""Framework contract and archive checks, without installing/running real ES."""
import base64,io,json,os,pathlib,subprocess,tarfile,tempfile,threading,unittest
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
ROOT=pathlib.Path(__file__).resolve().parents[1]
class FrameworkTest(unittest.TestCase):
    def setUp(self):
        self.events=[];events=self.events
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                if self.headers.get('Authorization')!='Bearer fixture-token':self.send_error(401);return
                events.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))));self.send_response(200);self.end_headers();self.wfile.write(b'{}')
            def log_message(self,*args):pass
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler);self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join()
    def run_framework(self,fail=False):
        conf={'nodes':[{'task_id':'test-task','token':'fixture-token','callback_url':f'http://127.0.0.1:{self.server.server_port}/api/callbacks/test-task','package_url':'http://127.0.0.1/package.tar.gz','sha256':'a'*64,'cluster_name':'fixture','node_name':'es-1','host_ip':'127.0.0.1','expected_nodes':1,'seed_hosts':['127.0.0.1'],'master_nodes':['es-1']}]}
        script=(ROOT/'scripts/framework/common.sh').read_text().replace('__CONFIG_BASE64__',base64.b64encode(json.dumps(conf).encode()).decode())
        script+='\ncomponent_preflight(){ '+('return 12;' if fail else ':;')+' }\ncomponent_download(){ :; }\ncomponent_install(){ :; }\ncomponent_start(){ :; }\ncomponent_verify(){ :; }\nINSTALL_DIR=/tmp/fixture-never-installed\nframework_main\n'
        env=os.environ.copy();env['YCYH_TARGET_IP']='127.0.0.1'
        return subprocess.run(['bash','-s'],input=script.encode(),env=env,capture_output=True,timeout=15)
    def test_success_visits_all_stages_and_verifies_before_success(self):
        result=self.run_framework();self.assertEqual(0,result.returncode,result.stderr.decode());self.assertEqual('STARTED',self.events[0]['phase']);self.assertEqual('SUCCEEDED',self.events[-1]['phase']);self.assertIn('VERIFYING',[e['phase'] for e in self.events]);self.assertEqual(list(range(1,len(self.events)+1)),[e['seq'] for e in self.events])
    def test_failure_keeps_exit_code_and_reports_failed(self):
        result=self.run_framework(True);self.assertEqual(12,result.returncode,result.stderr.decode());self.assertEqual('FAILED',self.events[-1]['phase']);self.assertNotIn('SUCCEEDED',[e['phase'] for e in self.events]);self.assertIn('exit=12',self.events[-1]['message'])
    def run_archive(self,mode):
        with tempfile.TemporaryDirectory() as tmp:
            p=pathlib.Path(tmp)
            with tarfile.open(p/'package.tar.gz','w:gz') as t:
                for name in ['elasticsearch-8.17.0/bin/elasticsearch','elasticsearch-8.17.0/jdk/bin/java']:
                    body=b'#!/bin/sh\nexit 0\n';m=tarfile.TarInfo(name);m.mode=0o755;m.size=len(body);t.addfile(m,io.BytesIO(body))
                if mode=='traversal':
                    m=tarfile.TarInfo('../escape');m.size=1;t.addfile(m,io.BytesIO(b'x'))
                if mode=='symlink':
                    m=tarfile.TarInfo('elasticsearch-8.17.0/out');m.type=tarfile.SYMTYPE;m.linkname='../../outside';t.addfile(m)
            script=(ROOT/'scripts/framework/common.sh').read_text()+'\n'+(ROOT/'scripts/components/elasticsearch/install.sh').read_text()+'\nSTAGING="$1"\nLOG_FILE="$1/log"\nINSTALL_DIR="$1/installed"\nCLUSTER_NAME=fixture\nNODE_NAME=es-1\nHOST_IP=127.0.0.1\nSEED_HOSTS=\'["127.0.0.1"]\'\nMASTER_NODES=\'["es-1"]\'\ncomponent_install\n'
            result=subprocess.run(['bash','-s','--',str(p)],input=script.encode(),capture_output=True,timeout=10)
            if mode=='valid':self.assertTrue((p/'installed/config/elasticsearch.yml').exists(),result.stderr.decode())
            else:self.assertFalse((p/'installed').exists())
            return result
    def test_valid_archive_is_configured(self):self.assertEqual(0,self.run_archive('valid').returncode)
    def test_archive_traversal_is_rejected(self):self.assertNotEqual(0,self.run_archive('traversal').returncode)
    def test_archive_escaping_symlink_is_rejected(self):self.assertNotEqual(0,self.run_archive('symlink').returncode)
if __name__=='__main__':unittest.main(verbosity=2)
