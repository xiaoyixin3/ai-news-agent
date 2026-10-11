import {api,hasSubscription,saveToken,clearToken} from './api.js';
import {date,element,renderBrief,renderEvents,renderRuns} from './render.js';
const form=document.querySelector('#preferences-form');
const save=document.querySelector('#save'),generate=document.querySelector('#generate'),notice=document.querySelector('#notice');
let page=1,total=0,selected='',activeRun='',poller=null,inboxPoller=null,refreshing=false,health=null;
let inboxMode='',inboxReady=false,knownBriefs=new Set();
const historyMode=document.querySelector('#history-mode'),cancelRun=document.querySelector('#cancel-run');
const drawBrief=brief=>renderBrief(brief,retry,cancelDelivery,deleteBrief);
function message(text,error=false){notice.textContent=text;notice.classList.toggle('error',error);notice.hidden=false}
function errorMessage(error){message(error.name==='TimeoutError'?'请求超时，请稍后重试':error.message,true)}
function terms(value){return [...new Set(value.split(/[,，、\n]/).map(item=>item.trim()).filter(Boolean))]}
function channelChanged(){const email=form.elements.email;const visible=form.elements.channel.value==='email';document.querySelector('#email-field').hidden=!visible;email.required=visible;document.querySelector('#channel-hint').hidden=!(visible&&health?.mode==='live'&&!health.smtp_ready)}
form.elements.channel.addEventListener('change',channelChanged);
function readProfile(){const values=new FormData(form);return {nickname:values.get('nickname').trim(),role:values.get('role').trim(),topics:terms(values.get('topics')),keywords:terms(values.get('keywords')),exclude:terms(values.get('exclude')),time:values.get('time'),timezone:values.get('timezone'),channel:values.get('channel'),email:values.get('email').trim(),enabled:form.elements.enabled.checked,max_items:Number(values.get('max_items')),lookback_hours:Number(values.get('lookback_hours'))}}
function fill(profile){for(const [key,value] of Object.entries(profile)){const input=form.elements[key];if(!input)continue;if(key==='enabled'){input.checked=value;continue}if(key==='timezone'&&![...input.options].some(option=>option.value===value))input.append(new Option(value,value));input.value=Array.isArray(value)?value.join('，'):value}channelChanged()}
form.addEventListener('submit',async event=>{
  event.preventDefault();save.disabled=true;save.textContent='保存中…';
  try{const existing=hasSubscription();const result=await api(existing?'/me':'/subscriptions',{method:existing?'PUT':'POST',body:JSON.stringify(readProfile())});if(result.token)saveToken(result.token);fill(result.profile);message('偏好已保存。可以生成简报，也会按设定时间自动生成。');await refresh();startInboxPolling()}
  catch(error){errorMessage(error)}finally{save.disabled=false;save.textContent='保存偏好'}
});
async function loadEvents(id,host,status){try{renderEvents(host,await api(`/runs/${id}/events`),status)}catch(error){host.replaceChildren(element('p',error.message,'run-error'))}}
async function retry(id,button,uncertain=false){if(uncertain&&!confirm('邮件可能已经送达。重新发送可能重复，确认仍要重试吗？'))return;button.disabled=true;try{await api(`/briefs/${id}/retry-delivery${uncertain?'?confirmDuplicateRisk=true':''}`,{method:'POST'});message('邮件重试已登记。');await refresh();startPolling()}catch(error){errorMessage(error);button.disabled=false}}
async function cancelDelivery(id){try{await api(`/briefs/${id}/cancel-delivery`,{method:'POST'});message('邮件投递已取消，站内简报保留。');await refresh()}catch(error){errorMessage(error)}}
async function deleteBrief(id){if(!confirm('删除这份简报及其邮件记录？'))return;try{await api(`/briefs/${id}`,{method:'DELETE'});selected='';await refresh();message('简报已删除。')}catch(error){errorMessage(error)}}
async function retryRun(id,button){button.disabled=true;try{const run=await api('/runs',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({retry_of:id})});activeRun=run.id;historyMode.value=health.mode;selected='';page=1;await refresh();startPolling()}catch(error){errorMessage(error);button.disabled=false}}
document.querySelector('#delete-subscription').addEventListener('click',async()=>{if(!confirm('永久删除当前订阅、历史简报和运行记录？此操作无法撤销。'))return;try{await api('/me',{method:'DELETE'});stopPolling();stopInboxPolling();clearToken();location.reload()}catch(error){errorMessage(error)}});
async function showBrief(id){selected=id;try{drawBrief(await api(`/briefs/${id}`))}catch(error){errorMessage(error)}}
async function refresh(){
  if(!hasSubscription()||refreshing)return;refreshing=true;
  const requestedMode=historyMode.value,requestedPage=page;
  try{
    document.querySelector('#delete-subscription').hidden=false;
    const [briefs,runs,stats]=await Promise.all([api(`/briefs?page=${requestedPage}&limit=10&mode=${requestedMode}`),api('/runs'),api(`/metrics?mode=${requestedMode}`)]);
    if(requestedMode!==historyMode.value||requestedPage!==page)return;
    if(page===1&&(!inboxReady||inboxMode!==historyMode.value)){inboxMode=historyMode.value;knownBriefs=new Set(briefs.items.map(brief=>brief.id));inboxReady=true}
    const percent=value=>value===null?'暂无统计':`${Math.round(value*100)}%`;const statsHost=document.querySelector('#metrics-content');statsHost.replaceChildren(element('p',`生成成功率 ${percent(stats.success_rate)} · 工具成功率 ${percent(stats.tool_success_rate)} · 邮件成功率 ${percent(stats.delivery_success_rate)}`),element('p',`生成耗时中位数 ${stats.latency_p50_ms===null?'暂无统计':stats.latency_p50_ms+' ms'} · 已知模型用量 ${stats.runs.input_tokens+stats.runs.output_tokens} · 未知用量请求 ${stats.runs.unknown_usage} 次`),element('p',stats.quality_note));
    statsHost.append(element('p',`已完成工具 ${stats.tools.attempts} 次 · 执行中止 ${stats.tools.interrupted||0} 次 · 未执行 ${stats.tools.not_executed||0} 次；工具成功率只统计已完成尝试。`));
    const active=runs.find(run=>['queued','running'].includes(run.status));
    const finished=activeRun&&!active?runs.find(run=>run.id===activeRun):null;
    if(finished?.status==='succeeded'&&finished.mode===historyMode.value){
      const produced=briefs.items.find(brief=>brief.run_id===activeRun);
      if(produced)selected=produced.id;
      else {page=1;startPolling();return}
    }
    total=briefs.total;const controls=document.querySelector('#history-controls');controls.hidden=!total;
    const select=document.querySelector('#history-select');select.replaceChildren(...briefs.items.map(brief=>new Option(`${date(brief.created_at)} · ${brief.title}`,brief.id)));
    if(briefs.items.length){if(!briefs.items.some(brief=>brief.id===selected))selected=briefs.items[0].id;select.value=selected;drawBrief(briefs.items.find(brief=>brief.id===selected))}else document.querySelector('#brief-content').replaceChildren(element('p','当前模式暂无简报。','subtle'));
    document.querySelector('#previous').disabled=page<=1;document.querySelector('#next').disabled=page*10>=total;
    renderRuns(runs.filter(run=>run.mode===historyMode.value),loadEvents,retryRun);cancelRun.hidden=!active;
    const status=document.querySelector('#generation-status');
    if(active){activeRun=active.id;generate.disabled=true;generate.textContent='生成中…';status.hidden=false;status.textContent=active.status==='queued'?'任务已排队，稍后开始生成。':'正在整理简报，可展开运行记录查看进度。';startPolling()}
    else{
      generate.disabled=false;generate.textContent='生成简报';status.hidden=true;
      if(activeRun){activeRun='';if(finished?.status==='failed')message(`生成失败：${finished.error||'请查看运行记录'}`,true);else if(finished?.status==='succeeded'){message('简报已生成，已保存在收件箱。');setTimeout(()=>refresh(),500)}}
      if(!briefs.items.some(brief=>['sending','pending'].includes(brief.delivery_status)))stopPolling();
    }
  }catch(error){if(error.status===401){clearToken();stopPolling();stopInboxPolling();message('本地订阅令牌已失效，请重新保存偏好创建订阅。',true)}else errorMessage(error)}
  finally{refreshing=false}
}
function startPolling(){if(!poller)poller=setInterval(()=>refresh(),2000)}
function stopPolling(){clearInterval(poller);poller=null}
async function checkInbox(){
  if(!hasSubscription())return;
  const mode=historyMode.value;
  try{
    const briefs=await api(`/briefs?page=1&limit=10&mode=${mode}`);
    if(mode!==historyMode.value)return;
    if(inboxMode!==mode){inboxMode=mode;inboxReady=false;knownBriefs=new Set()}
    const incoming=briefs.items.filter(brief=>!knownBriefs.has(brief.id));
    for(const brief of briefs.items)knownBriefs.add(brief.id);
    if(knownBriefs.size>500)knownBriefs=new Set(briefs.items.map(brief=>brief.id));
    if(inboxReady&&incoming.length){page=1;message(`收到 ${incoming.length} 份新简报，已自动更新收件箱。`);await refresh()}
    inboxReady=true;
  }catch(error){if(error.status===401){clearToken();stopPolling();stopInboxPolling();message('订阅令牌已失效，请重新保存偏好。',true)}else errorMessage(error)}
}
function startInboxPolling(){if(!inboxPoller){checkInbox();inboxPoller=setInterval(checkInbox,30000)}}
function stopInboxPolling(){clearInterval(inboxPoller);inboxPoller=null;inboxReady=false;knownBriefs=new Set()}
generate.addEventListener('click',async()=>{
  if(!hasSubscription()){message('请先填写昵称并保存偏好。',true);form.elements.nickname.focus();return}
  generate.disabled=true;generate.textContent='提交中…';
  try{historyMode.value=health.mode;const run=await api('/runs',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()}});activeRun=run.id;page=1;selected='';notice.hidden=true;await refresh();startPolling()}
  catch(error){errorMessage(error);generate.disabled=false;generate.textContent='生成简报'}
});
historyMode.addEventListener('change',async()=>{page=1;selected='';inboxReady=false;await refresh();await checkInbox()});
cancelRun.addEventListener('click',async()=>{try{await api(`/runs/${activeRun}/cancel`,{method:'POST'});message('生成已取消。');await refresh()}catch(error){errorMessage(error)}});
document.querySelector('#history-select').addEventListener('change',event=>showBrief(event.target.value));
document.querySelector('#previous').addEventListener('click',async()=>{page--;selected='';await refresh()});
document.querySelector('#next').addEventListener('click',async()=>{page++;selected='';await refresh()});
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&hasSubscription()){refresh();checkInbox()}});
window.addEventListener('pagehide',()=>{stopPolling();stopInboxPolling()});
window.addEventListener('pageshow',()=>{if(health&&hasSubscription()){refresh();startInboxPolling()}});
async function initialize(){
  try{health=await api('/health');const mode=document.querySelector('#mode');mode.textContent=health.mode==='demo'?'演示模式':'真实模式';mode.title=health.mode==='demo'?'脚本化模拟响应与合成样例，不调用 DeepSeek，不发送邮件。':`模型：${health.model}`;if(!health.ready)message('真实模式尚未配置模型密钥，请在服务端环境变量中配置。',true);
    historyMode.value=health.mode;channelChanged();if(hasSubscription()){fill((await api('/me')).profile);await refresh();startInboxPolling()}
  }catch(error){if(error.status===401){clearToken();message('订阅令牌已失效，请重新保存偏好。',true)}else errorMessage(error)}
}
channelChanged();initialize();
