export const labels={queued:'排队中',running:'生成中',succeeded:'已完成',failed:'失败',inbox:'已保存到站内',demo_inbox:'演示 · 已保存到站内',sending:'邮件发送中',sent:'邮件已发送'};
export const date=value=>value?new Intl.DateTimeFormat('zh-CN',{dateStyle:'medium',timeStyle:'short'}).format(new Date(value)):'';
export function element(tag,text,className){const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node}
export function renderBrief(brief,retry){
  const host=document.querySelector('#brief-content');host.replaceChildren();
  host.append(element('h3',brief.title,'brief-heading'));
  const meta=element('div',undefined,'brief-meta');meta.append(element('span',date(brief.created_at)),element('span',labels[brief.delivery_status]||(brief.delivery_status==='failed'?'邮件投递失败':brief.delivery_status)));
  host.append(meta,element('p',brief.intro,'brief-intro'));
  if(brief.delivery_error){const box=element('div',undefined,'delivery-error');box.append(element('div',brief.delivery_error));if(brief.delivery_status==='failed'){const button=element('button','重试邮件','quiet');button.type='button';button.onclick=()=>retry(brief.id,button);box.append(button)}host.append(box)}
  brief.items.forEach((item,index)=>{
    const article=element('article',undefined,'news-item');article.append(element('h4',`${index+1}. ${item.title}`),element('p',item.summary),element('p',`与你相关：${item.why_it_matters}`,'why'));
    const source=element('div',undefined,'source');source.append(element('span',item.source),element('span',date(item.published_at)));
    try{const url=new URL(item.url);if(['http:','https:'].includes(url.protocol)){const link=element('a','查看来源');link.href=url.href;link.target='_blank';link.rel='noopener noreferrer';source.append(link)}}catch{}
    article.append(source);host.append(article);
  });
}
export function renderEvents(host,events){
  host.replaceChildren();
  if(!events.length){host.append(element('p','正在等待执行…','subtle'));return}
  events.forEach(event=>{
    const row=element('div',undefined,'event');const payload=event.payload;
    const name=event.kind==='tool'?payload.name:event.kind==='model'?`第 ${payload.round} 轮 · ${payload.tool_count} 次工具请求`:event.kind==='delivery'?'投递状态':'运行错误';
    row.append(element('span',name,'event-name'),element('span',event.kind==='tool'?`${payload.elapsed_ms} ms`:date(event.created_at),'event-time'));
    if(event.kind==='tool'){
      row.append(element('div',payload.result.ok?'执行成功':`执行失败：${payload.result.message}`,'subtle'));
      const details=element('details');details.append(element('summary','调用详情'),element('pre',JSON.stringify({参数:payload.arguments,结果:payload.result},null,2)));row.append(details);
    }else if(event.kind==='error'){row.append(element('p',payload.message,'run-error'))}
    host.append(row);
  });
}
export function renderRuns(runs,loadEvents){
  const host=document.querySelector('#runs-content');
  const opened=new Set([...host.querySelectorAll('details.run-row[open]')].map(node=>node.dataset.id));
  if(!runs.length)return;
  host.replaceChildren();
  runs.forEach(run=>{
    const row=element('details',undefined,'run-row');row.dataset.id=run.id;row.open=opened.has(run.id);
    const summary=element('summary');summary.append(element('span',`${date(run.created_at)} · ${run.trigger==='scheduled'?'每日自动简报':'手动生成'}${run.mode==='demo'?' · 演示':''}`,'run-title'),element('span',labels[run.status]||run.status,`run-state ${run.status}`));
    const events=element('div',undefined,'events');row.append(summary);if(run.error)row.append(element('p',run.error,'run-error'));row.append(events);
    let loaded=false;row.addEventListener('toggle',async()=>{if(row.open&&!loaded){loaded=true;await loadEvents(run.id,events)}});
    host.append(row);if(row.open){loaded=true;loadEvents(run.id,events)}
  });
}
