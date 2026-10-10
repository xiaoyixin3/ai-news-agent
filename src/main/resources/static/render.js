export const labels={queued:'排队中',running:'生成中',succeeded:'已完成',failed:'失败',cancelled:'已取消',pending:'邮件等待发送',unknown:'邮件结果不确定',inbox:'已保存到站内',demo_inbox:'演示 · 已保存到站内',sending:'邮件发送中',sent:'邮件已发送'};
export const phases={PREPARING:'准备资料',THINKING:'模型决策',WAITING_TOOL:'执行工具',VALIDATING:'校验候选',COMMITTING:'保存简报'};
export const date=value=>value?new Intl.DateTimeFormat('zh-CN',{dateStyle:'medium',timeStyle:'short'}).format(new Date(value)):'';
export function element(tag,text,className){const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node}
export function renderBrief(brief,retry,cancelMail,deleteBrief){
  const host=document.querySelector('#brief-content');host.replaceChildren();
  host.append(element('h3',brief.title,'brief-heading'));
  const meta=element('div',undefined,'brief-meta');meta.append(element('span',date(brief.created_at)),element('span',labels[brief.delivery_status]||(brief.delivery_status==='failed'?'邮件投递失败':brief.delivery_status)));
  host.append(meta,element('p',brief.intro,'brief-intro'));
  if(brief.delivery_recipient)host.append(element('p',`邮件收件人：${brief.delivery_recipient}`,'subtle'));
  if(brief.delivery_error){const box=element('div',undefined,'delivery-error');box.append(element('div',brief.delivery_error));if(['failed','unknown'].includes(brief.delivery_status)){const button=element('button',brief.delivery_status==='unknown'?'确认后重试邮件':'重试邮件','quiet');button.type='button';button.onclick=()=>retry(brief.id,button,brief.delivery_status==='unknown');box.append(button)}host.append(box)}
  const actions=element('div',undefined,'action-row');if(cancelMail&&['pending','failed'].includes(brief.delivery_status)){const button=element('button','取消邮件投递','quiet');button.type='button';button.onclick=()=>cancelMail(brief.id);actions.append(button)}if(deleteBrief&&!['sending','unknown'].includes(brief.delivery_status)){const button=element('button','删除这份简报','quiet');button.type='button';button.onclick=()=>deleteBrief(brief.id);actions.append(button)}if(actions.childNodes.length)host.append(actions);
  brief.items.forEach((item,index)=>{
    const article=element('article',undefined,'news-item');article.append(element('h4',`${index+1}. ${item.title}`),element('p',item.summary),element('p',`与你相关：${item.why_it_matters}`,'why'));
    const source=element('div',undefined,'source');source.append(element('span',item.source),element('span',date(item.published_at)));
    if(item.evidence_scope)source.append(element('span',item.evidence_scope==='extracted_body'?'已读取正文':'依据 RSS 摘要'));if(item.body_truncated)source.append(element('span','正文已截断'));
    try{const url=new URL(item.url);if(['http:','https:'].includes(url.protocol)){const link=element('a','查看来源');link.href=url.href;link.target='_blank';link.rel='noopener noreferrer';source.append(link)}}catch{}
    article.append(source);host.append(article);
  });
}
export function renderEvents(host,events){
  host.replaceChildren();
  if(!events.length){host.append(element('p','正在等待执行…','subtle'));return}
  events.forEach(event=>{
    if(event.kind==='tool_start'&&events.some(end=>end.kind==='tool'&&end.step_id===event.step_id))return;
    const row=element('div',undefined,'event');const payload=event.payload;
    const name=['tool','tool_start'].includes(event.kind)?(payload.name||event.tool_name||'工具调用'):event.kind==='model'?`第 ${payload.round||'?'} 轮 · ${payload.tool_count||0} 次工具请求`:event.kind==='delivery'?'投递状态':event.kind==='terminal'?'运行结束':event.kind==='error'?'运行错误':event.summary;
    row.append(element('span',name,'event-name'),element('span',event.kind==='tool'?`${payload.elapsed_ms} ms`:date(event.created_at),'event-time'));
    if(event.kind==='tool'){
      row.append(element('div',payload.result?.ok?'执行成功':payload.result?`执行失败：${payload.result.message}`:event.summary,'subtle'));
      const details=element('details');details.append(element('summary','调用详情'),element('pre',JSON.stringify({参数:payload.arguments,结果:payload.result,详情状态:event.payload_state},null,2)));row.append(details);
      if(!['INLINE','EXTERNAL'].includes(event.payload_state))row.append(element('p',event.payload_state==='MISSING'?'审计详情缺失，摘要仍保留':event.payload_state==='EXPIRED'?'审计详情已到保留期':'详情因配额省略','subtle'));
    }else if(event.kind==='tool_start'){row.append(element('p','工具执行中…','subtle'))}else if(event.kind==='error'){row.append(element('p',payload.message,'run-error'))}else if(event.kind==='model'&&payload.usage?.prompt_tokens!==undefined){row.append(element('p',`本轮用量：输入 ${payload.usage.prompt_tokens}，输出 ${payload.usage.completion_tokens}`,'subtle'))}
    host.append(row);
  });
}
export function renderRuns(runs,loadEvents,retryRun){
  const host=document.querySelector('#runs-content');
  const opened=new Set([...host.querySelectorAll('details.run-row[open]')].map(node=>node.dataset.id));
  if(!runs.length){host.replaceChildren(element('p','当前模式暂无运行记录。','subtle'));return}
  host.replaceChildren();
  runs.forEach(run=>{
    const row=element('details',undefined,'run-row');row.dataset.id=run.id;row.open=opened.has(run.id);
    const summary=element('summary');summary.append(element('span',`${date(run.created_at)} · ${run.trigger==='scheduled'?'每日自动简报':'手动生成'}${run.mode==='demo'?' · 演示':''}`,'run-title'),element('span',labels[run.status]||run.status,`run-state ${run.status}`));
    const events=element('div',undefined,'events');row.append(summary);if(run.error)row.append(element('p',run.error,'run-error'));row.append(events);
    if(run.phase)row.append(element('p',phases[run.phase]||run.phase,'subtle'));if(run.model_attempts)row.append(element('p',`模型请求 ${run.model_attempts} 次 · 工具请求 ${run.tool_calls} 次 · 已知用量 ${run.known_input_tokens+run.known_output_tokens} · 用量未知的请求 ${run.unknown_usage_attempts} 次`,'subtle'));
    if(retryRun&&['failed','cancelled'].includes(run.status)){const button=element('button','重新生成','quiet');button.type='button';button.onclick=()=>retryRun(run.id,button);row.append(button)}
    let loaded=false;row.addEventListener('toggle',async()=>{if(row.open&&!loaded){loaded=true;await loadEvents(run.id,events)}});
    host.append(row);if(row.open){loaded=true;loadEvents(run.id,events)}
  });
}
