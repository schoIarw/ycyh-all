"use strict";
const $=s=>document.querySelector(s);
let selected=null,packages=[],busy=false;
const labels={QUEUED:'准备申请',WAITING_APPROVAL:'等待审批',RUNNING:'执行中',DEPLOYING:'部署中',SUCCEEDED:'部署成功',FAILED:'部署失败',UNKNOWN:'状态待核查',REJECTED:'审批驳回'};
function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
function badge(state){return el('span',labels[state]||state,'badge '+state);}
async function api(path,options={}){
 const headers={...(options.headers||{})};
 if(options.method){headers[$('meta[name=csrf-header]').content]=$('meta[name=csrf-token]').content;headers['Content-Type']='application/json';}
 const r=await fetch(path,{...options,headers});if(r.redirected){location.href='/login';throw Error('会话已过期');}
 if(!r.ok){let message='请求失败（'+r.status+'）';try{const j=await r.json();message=j.message||message;}catch(_){}throw Error(message);}
 return r.json();
}
async function refresh(){
 if(busy)return;busy=true;
 try{
 const list=await api('/api/clusters');const body=$('#clusters');body.replaceChildren();$('#empty').hidden=list.length>0;
 for(const c of list){const row=el('tr');let cell=el('td',c.name);cell.append(el('small',c.id));row.append(cell,el('td',c.package_path),el('td',String(c.tasks.length)));cell=el('td');cell.append(badge(c.status));row.append(cell,el('td',new Date(c.created_at).toLocaleString()));cell=el('td');const view=el('button','详情 / 日志','link');view.onclick=()=>{selected=c.id;refreshDetails(c);};const approval=el('a','打开审批');approval.href=c.approvalUrl;approval.target='_blank';approval.rel='noopener';cell.append(view,el('br'),approval);row.append(cell);body.append(row);}
 if(selected){const c=list.find(c=>c.id===selected);if(c)await refreshDetails(c);}
 $('#page-error').textContent='';
 }catch(e){$('#page-error').textContent=e.message;}finally{busy=false;}
}
async function refreshDetails(c){
 $('#details').hidden=false;$('#detail-title').textContent=c.name+' · 部署详情';const box=$('#task-details');box.replaceChildren();
 for(const t of c.tasks){const p=el('p');p.append(el('b',t.host_ip+' / '+t.username+'　'),badge(t.status),el('span','　阶段：'+t.phase+'　'+(t.detail||'')));p.append(el('small','任务：'+t.id+'　脚本：'+(t.script_uuid||'准备中')));box.append(p);}
 try{const events=await api('/api/clusters/'+c.id+'/events');if(selected===c.id)$('#events').textContent=events.map(e=>`${new Date(e.created_at).toLocaleString()} [${e.task_id.slice(0,8)}] [${e.phase}] ${e.message}`).join('\n')||'暂无回传；等待审批或执行开始';}catch(e){$('#events').textContent=e.message;}
}
$('#close-details').onclick=()=>{selected=null;$('#details').hidden=true;};
$('#add-cluster').onclick=async()=>{
 $('#form-error').textContent='';$('#deploy-dialog').showModal();$('#submit').disabled=true;
 try{
 const [ps,hosts]=await Promise.all([api('/api/packages'),api('/api/hosts')]);packages=ps;$('#package').replaceChildren();for(const p of ps){const o=el('option',p.name);o.value=p.path;$('#package').append(o);}$('#hosts').replaceChildren();for(const h of hosts){const l=el('label');const input=el('input');input.type='checkbox';input.value=h.ip;l.append(input,document.createTextNode(h.ip+' / '+h.username));$('#hosts').append(l);}if(!ps.length)throw Error('仓库中没有 tar.gz 安装包，请先放入 ES 安装包');packageInfo();$('#submit').disabled=false;
 }catch(e){$('#form-error').textContent=e.message;}
};
function packageInfo(){const p=packages.find(p=>p.path===$('#package').value);$('#package-info').textContent=p?'大小：'+(p.size/1024/1024).toFixed(1)+' MiB · SHA-256：'+p.sha256:'';}
$('#package').onchange=packageInfo;
$('#cancel').onclick=()=>$('#deploy-dialog').close();
$('#deploy-form').onsubmit=async e=>{
 e.preventDefault();const hostIps=[...document.querySelectorAll('#hosts input:checked')].map(n=>n.value);if(hostIps.length<1||hostIps.length>8){$('#form-error').textContent='请选择1—8台主机';return;}
 $('#submit').disabled=true;
 try{const c=await api('/api/clusters',{method:'POST',body:JSON.stringify({name:$('#cluster-name').value,packagePath:$('#package').value,hostIps})});selected=c.id;$('#deploy-dialog').close();await refresh();}catch(e){$('#form-error').textContent=e.message;}finally{$('#submit').disabled=false;}
};
refresh();setInterval(refresh,5000);
