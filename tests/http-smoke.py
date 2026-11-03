#!/usr/bin/env python3
"""Exercise real HTTP/session/DB/approval flow. Does not execute a remote install."""
import http.cookiejar,json,os,re,time,urllib.request,urllib.parse,urllib.error,uuid
OV=os.environ.get('OVERSIGHT_URL','http://127.0.0.1:8081')
CM=os.environ.get('CLUSTERMGR_URL','http://127.0.0.1:8080')
KEY=os.environ.get('OVERSIGHT_API_KEY','')
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
def v9(path,body=None,token=None,query=None):
    headers={'secretToken':token} if token else {}
    url=OV+'/api/'+path
    if query:url+='?'+urllib.parse.urlencode(query)
    if body is not None:headers['Content-Type']='application/json'
    value=json.loads(request(anon,url,None if body is None else json.dumps(body).encode(),headers))
    assert value.get('code')=='10000',path+': '+str(value)
    return value['data']
operator=v9('system/authority/getSecretToken',query={'username':os.environ.get('OVERSIGHT_OPERATOR','operator')})
assert 'exipreTime' in operator and 'expireTime' not in operator
operator_token=operator['token']
missing=json.loads(request(anon,OV+'/api/scriptApi/taskApply/listTaskApply',b'{}',{'Content-Type':'application/json'}));assert missing['code']=='10602'
script=v9('scriptApi/myScript/getScriptDetail',{'scriptName':'ycyh_es_install'},operator_token)
assert script['scriptVersionDto']['id']>9007199254740991
assert script['scriptVersionDto']['parameterValidationDtoList'][0]['paramName']=='configBase64'
approver=login(OV,os.environ.get('OVERSIGHT_ADMIN_USER','approver'),os.environ['OVERSIGHT_ADMIN_PASSWORD'])
# A >64KiB script tests real MySQL/PostgreSQL chunk persistence through the admin UI.
source='#!/bin/bash\n#'+'x'*80000+'\nprintf 0\n'
name='fixture-'+uuid.uuid4().hex[:8]
registration={'scripts':[{'scriptName':name,'scriptNameZh':name,'scriptType':'sh','execuser':'es','scriptVersionDto':{'level':3,'scriptContentDto':{'content':source}}}]}
page=request(approver,OV+'/catalog');data,headers=multipart({'_csrf':csrf(page)},json.dumps(registration).encode());headers.pop('X-API-Key',None)
request(approver,OV+'/catalog/import',data,headers)
assert v9('scriptApi/myScript/getScriptDetail',{'scriptName':name},operator_token)['scriptVersionDto']['scriptContentDto']['content']==source
admin=login(CM,os.environ.get('ADMIN_USER','admin'),os.environ['ADMIN_PASSWORD'])
page=request(admin,CM+'/');token=re.search(r'name="csrf-token" content="([^"]+)"',page).group(1)
packages=json.loads(request(admin,CM+'/api/packages'));hosts=json.loads(request(admin,CM+'/api/hosts'));assert packages and hosts
payload={'name':'smoke-'+uuid.uuid4().hex[:8],'packagePath':packages[0]['path'],'hostIps':[hosts[0]['ip']]}
c=json.loads(request(admin,CM+'/api/clusters',json.dumps(payload).encode(),{'Content-Type':'application/json','X-CSRF-TOKEN':token}))
cluster=poll(lambda:next(x for x in json.loads(request(admin,CM+'/api/clusters')) if x['id']==c['id']),lambda x:x['status'] in ('WAITING_APPROVAL','UNKNOWN'))
assert cluster['status']=='WAITING_APPROVAL',cluster
page=request(approver,cluster['approvalUrl']);assert '批准，等待启动' in page
request(approver,cluster['approvalUrl']+'/reject',urllib.parse.urlencode({'_csrf':csrf(page),'reason':'Smoke: intentionally rejected; no SSH allowed'}).encode(),{'Content-Type':'application/x-www-form-urlencoded'})
cluster=poll(lambda:next(x for x in json.loads(request(admin,CM+'/api/clusters')) if x['id']==c['id']),lambda x:x['status']=='REJECTED')
assert len(cluster['tasks'])==1
print('PASS: V9 token/header, int64 IDs, >64KiB script persistence, real UI/CSRF, one version-based deployment application, rejection propagated; no remote installation')
