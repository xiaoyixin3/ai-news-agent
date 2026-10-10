import {api,hasSubscription,saveToken,clearToken} from './api.js';
import {date,element,renderBrief,renderEvents,renderRuns} from './render.js';
const form=document.querySelector('#preferences-form');
const save=document.querySelector('#save'),generate=document.querySelector('#generate'),notice=document.querySelector('#notice');
let page=1,total=0,selected='',activeRun='',poller=null,refreshing=false,health=null;
function message(text,error=false){notice.textContent=text;notice.classList.toggle('error',error);notice.hidden=false}
function errorMessage(error){message(error.name==='TimeoutError'?'请求超时，请稍后重试':error.message,true)}
function terms(value){return [...new Set(value.split(/[,，、\n]/).map(item=>item.trim()).filter(Boolean))]}
function channelChanged(){const email=form.elements.email;const visible=form.elements.channel.value==='email';document.querySelector('#email-field').hidden=!visible;email.required=visible}
form.elements.channel.addEventListener('change',channelChanged);
function readProfile(){const values=new FormData(form);return {nickname:values.get('nickname').trim(),role:values.get('role').trim(),topics:terms(values.get('topics')),keywords:terms(values.get('keywords')),exclude:terms(values.get('exclude')),time:values.get('time'),timezone:values.get('timezone'),channel:values.get('channel'),email:values.get('email').trim(),enabled:form.elements.enabled.checked,max_items:Number(values.get('max_items')),lookback_hours:Number(values.get('lookback_hours'))}}
function fill(profile){for(const [key,value] of Object.entries(profile)){const input=form.elements[key];if(!input)continue;if(key==='enabled'){input.checked=value;continue}if(key==='timezone'&&![...input.options].some(option=>option.value===value))input.append(new Option(value,value));input.value=Array.isArray(value)?value.join('，'):value}channelChanged()}
form.addEventListener('submit',async event=>{
  event.preventDefault();save.disabled=true;save.textContent='保存中…';
  try{const existing=hasSubscription();const result=await api(existing?'/me':'/subscriptions',{method:existing?'PUT':'POST',body:JSON.stringify(readProfile())});if(result.token)saveToken(result.token);fill(result.profile);message('偏好已保存。可以生成简报，也会按设定时间自动生成。');await refresh()}
  catch(error){errorMessage(error)}finally{save.disabled=false;save.textContent='保存偏好'}
});
async function loadEvents(id,host){try{renderEvents(host,await api(`/runs/${id}/events`))}catch(error){host.replaceChildren(element('p',error.message,'run-error'))}}
async function retry(id,button){button.disabled=true;try{await api(`/briefs/${id}/retry-delivery`,{method:'POST'});message('邮件重试已提交。');await refresh();startPolling()}catch(error){errorMessage(error);button.disabled=false}}
async function showBrief(id){selected=id;try{renderBrief(await api(`/briefs/${id}`),retry)}catch(error){errorMessage(error)}}
async function refresh(){
  if(!hasSubscription()||refreshing)return;refreshing=true;
  try{
    const [briefs,runs]=await Promise.all([api(`/briefs?page=${page}&limit=10`),api('/runs')]);
    const active=runs.find(run=>['queued','running'].includes(run.status));
    const finished=activeRun&&!active?runs.find(run=>run.id===activeRun):null;
    if(finished?.status==='succeeded'){
      const produced=briefs.items.find(brief=>brief.run_id===activeRun);
      if(produced)selected=produced.id;
      else {page=1;startPolling();return}
    }
    total=briefs.total;const controls=document.querySelector('#history-controls');controls.hidden=!total;
    const select=document.querySelector('#history-select');select.replaceChildren(...briefs.items.map(brief=>new Option(`${date(brief.created_at)} · ${brief.title}`,brief.id)));
    if(briefs.items.length){if(!briefs.items.some(brief=>brief.id===selected))selected=briefs.items[0].id;select.value=selected;renderBrief(briefs.items.find(brief=>brief.id===selected),retry)}
    document.querySelector('#previous').disabled=page<=1;document.querySelector('#next').disabled=page*10>=total;
    renderRuns(runs,loadEvents);
    const status=document.querySelector('#generation-status');
    if(active){activeRun=active.id;generate.disabled=true;generate.textContent='生成中…';status.hidden=false;status.textContent=active.status==='queued'?'任务已排队，稍后开始生成。':'正在整理简报，可展开运行记录查看进度。';startPolling()}
    else{
      generate.disabled=false;generate.textContent='生成简报';status.hidden=true;
      if(activeRun){activeRun='';if(finished?.status==='failed')message(`生成失败：${finished.error||'请查看运行记录'}`,true);else if(finished?.status==='succeeded'){message('简报已生成，已保存在收件箱。');setTimeout(()=>refresh(),500)}}
      if(!briefs.items.some(brief=>brief.delivery_status==='sending'))stopPolling();
    }
  }catch(error){if(error.status===401){clearToken();stopPolling();message('本地订阅令牌已失效，请重新保存偏好创建订阅。',true)}else errorMessage(error)}
  finally{refreshing=false}
}
function startPolling(){if(!poller)poller=setInterval(()=>refresh(),2000)}
function stopPolling(){clearInterval(poller);poller=null}
generate.addEventListener('click',async()=>{
  if(!hasSubscription()){message('请先填写昵称并保存偏好。',true);form.elements.nickname.focus();return}
  generate.disabled=true;generate.textContent='提交中…';
  try{const run=await api('/runs',{method:'POST'});activeRun=run.id;page=1;selected='';notice.hidden=true;await refresh();startPolling()}
  catch(error){errorMessage(error);generate.disabled=false;generate.textContent='生成简报'}
});
document.querySelector('#history-select').addEventListener('change',event=>showBrief(event.target.value));
document.querySelector('#previous').addEventListener('click',async()=>{page--;selected='';await refresh()});
document.querySelector('#next').addEventListener('click',async()=>{page++;selected='';await refresh()});
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&hasSubscription())refresh()});
async function initialize(){
  try{health=await api('/health');const mode=document.querySelector('#mode');mode.textContent=health.mode==='demo'?'演示模式':'真实模式';mode.title=health.mode==='demo'?'脚本化模拟响应与合成样例，不调用 DeepSeek，不发送邮件。':`模型：${health.model}`;if(!health.ready)message('真实模式尚未配置模型密钥，请在服务端环境变量中配置。',true);
    if(hasSubscription()){fill((await api('/me')).profile);await refresh()}
  }catch(error){if(error.status===401){clearToken();message('订阅令牌已失效，请重新保存偏好。',true)}else errorMessage(error)}
}
channelChanged();initialize();
