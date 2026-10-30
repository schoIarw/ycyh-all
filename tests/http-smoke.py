#!/usr/bin/env python3
"""Exercise real HTTP/session/DB/approval flow. Does not execute a remote install."""
import http.cookiejar,json,os,re,time,urllib.request,urllib.parse,urllib.error,uuid
OV=os.environ.get('OVERSIGHT_URL','http://127.0.0.1:8081')
CM=os.environ.get('CLUSTERMGR_URL','http://127.0.0.1:8080')
KEY=os.environ['OVERSIGHT_API_KEY']
def client():return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def request(client,url,data=None,headers=None):
    with client.open(urllib.request.Request(url,data=data,headers=headers or {}),timeout=35) as r:return r.read().decode()
def csrf(html):
    # Thymeleaf forms emit name="_csrf" value="..." (attribute ordering can vary).
    tags=re.findall(r'<input[^>]+>',html)
    for tag in tags:
        if re.search(r'name="_csrf"',tag):return re.search(r'value="([^"]+)"',tag).group(1)
    raise AssertionError('CSRF input missing')
def login(base,user,password):
    c=client();page=request(c,base+'/login')
    result=request(c,base+'/login',urllib.parse.urlencode({'username':user,'password':password,'_csrf':csrf(page)}).encode(),{'Content-Type':'application/x-www-form-urlencoded'})
    assert 'name="password"' not in result, "Login failed: "+str(re.findall(r'<p[^>]*>(.*?)</p>',result))
    return c
def multipart(fields,file):
    boundary='----ycyh'+uuid.uuid4().hex;chunks=[]
    for key,value in fields.items():chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
    chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="smoke.sh"\r\nContent-Type: text/plain\r\n\r\n'.encode()+file+b'\r\n');chunks.append(f'--{boundary}--\r\n'.encode())
    return b''.join(chunks),{'Content-Type':'multipart/form-data; boundary='+boundary,'X-API-Key':KEY}
def poll(fun,check):
    deadline=time.monotonic()+35
    while time.monotonic()<deadline:
        value=fun()
        if check(value):return value
        time.sleep(.3)
    raise AssertionError('Timed out waiting for expected status')
anon=client()
try:request(anon,OV+'/api/v1/applications/invalid');raise AssertionError('API key bypass')
except urllib.error.HTTPError as e:assert e.code==401
approver=login(OV,os.environ.get('OVERSIGHT_ADMIN_USER','approver'),os.environ['OVERSIGHT_ADMIN_PASSWORD'])
data,headers=multipart({'type':'SHELL'},b'#!/bin/bash\nexit 0\n')
app=json.loads(request(anon,OV+'/api/v1/scripts',data,headers));assert app['status']=='PENDING'
page=request(approver,OV+'/approvals/'+app['applicationId']);assert '批准并执行' in page
request(approver,OV+'/approvals/'+app['applicationId']+'/approve',urllib.parse.urlencode({'_csrf':csrf(page),'reason':'HTTP smoke test'}).encode(),{'Content-Type':'application/x-www-form-urlencoded'})
assert json.loads(request(anon,OV+'/api/v1/applications/'+app['applicationId'],headers={'X-API-Key':KEY}))['result']==1
admin=login(CM,os.environ.get('ADMIN_USER','admin'),os.environ['ADMIN_PASSWORD'])
page=request(admin,CM+'/');token=re.search(r'name="csrf-token" content="([^"]+)"',page).group(1)
packages=json.loads(request(admin,CM+'/api/packages'));hosts=json.loads(request(admin,CM+'/api/hosts'));assert packages and hosts
payload={'name':'smoke-'+uuid.uuid4().hex[:8],'packagePath':packages[0]['path'],'hostIps':[hosts[0]['ip']]}
c=json.loads(request(admin,CM+'/api/clusters',json.dumps(payload).encode(),{'Content-Type':'application/json','X-CSRF-TOKEN':token}))
cluster=poll(lambda:next(x for x in json.loads(request(admin,CM+'/api/clusters')) if x['id']==c['id']),lambda x:x['status']=='WAITING_APPROVAL')
page=request(approver,cluster['approvalUrl']);assert 'DEPLOYMENT' in page
request(approver,cluster['approvalUrl']+'/reject',urllib.parse.urlencode({'_csrf':csrf(page),'reason':'Smoke: intentionally rejected; no SSH allowed'}).encode(),{'Content-Type':'application/x-www-form-urlencoded'})
cluster=poll(lambda:next(x for x in json.loads(request(admin,CM+'/api/clusters')) if x['id']==c['id']),lambda x:x['status']=='REJECTED')
assert len(cluster['tasks'])==1
print('PASS: real HTTP login, CSRF, database persistence, approved upload, one combined deployment request, rejection propagated; no remote execution')
