const TOKEN_KEY='zaozhi-subscription-token';
let token=localStorage.getItem(TOKEN_KEY)||'';
export const hasSubscription=()=>Boolean(token);
export function saveToken(value){token=value;localStorage.setItem(TOKEN_KEY,value)}
export function clearToken(){token='';localStorage.removeItem(TOKEN_KEY)}
export async function api(path,options={}){
  const headers={'Content-Type':'application/json',...(token?{'Authorization':`Bearer ${token}`}:{})};
  const response=await fetch(`/api${path}`,{...options,headers,signal:AbortSignal.timeout(15000)});
  const result=await response.json();
  if(!response.ok){const error=new Error(result.error||'请求失败，请稍后再试');error.status=response.status;throw error}
  return result;
}
