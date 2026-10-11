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
