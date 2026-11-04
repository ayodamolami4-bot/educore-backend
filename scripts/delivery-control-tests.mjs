import assert from 'node:assert/strict';

export async function deliveryControlTests(u,f) {
  const {request,key,poll,passed,pushMessages,bootstrap,activate,login,choose}=u;const {school,admin,parent,children,accounts}=f;
  const base=`/api/v1/schools/${school}`;
  const call=async(method,path,body,version,expected=200,token=admin.accessToken,k=key())=>(await request(base+path,{method,body,token,expected,headers:{'Idempotency-Key':k,...(version===undefined?{}:{'If-Match':`"v${version}"`})}})).body;
  const get=(path,token)=>call('GET',path,undefined,undefined,200,token);
  const pushToken='ExpoPushToken[SyntheticManualRetryDevice]';await request('/api/v1/me/push-devices',{method:'POST',token:parent.accessToken,headers:{'Idempotency-Key':key()},body:{installationId:key(),platform:'ANDROID',pushToken}});
  const c=await call('POST','/complaints',{studentId:children[0].id,category:'GENERAL',subject:'Private manual delivery concern',body:'PRIVATE TEXT MUST NOT REACH A DELIVERY CONSOLE'},undefined,200,parent.accessToken);
  const notification=await poll(async()=>{const x=(await request(`/api/v1/me/notifications?schoolId=${school}&category=CASES`,{token:parent.accessToken})).body;return x.items.find(n=>n.target.resourceId===c.id)||false;},'manual retry notification');
  const failed=await poll(async()=>{const d=await get(`/delivery/notifications/${notification.id}`);return d.push.some(p=>p.state==='FAILED'&&p.errorCode==='MessageTooBig')?d:false;},'provider-rejected delivery');
  assert.equal(failed.providerReceiptIsReadConfirmation,false);assert.ok(!JSON.stringify(failed).includes('PRIVATE TEXT'));assert.ok(!JSON.stringify(failed).includes(pushToken));
  const job=failed.push.find(p=>p.errorCode==='MessageTooBig'),retryKey=key(),body={channel:'PUSH',deliveryId:job.id,reason:'Operator reviewed and corrected the synthetic provider delivery failure'};
  const queued=await call('POST',`/delivery/notifications/${notification.id}/retry`,body,failed.version,200,admin.accessToken,retryKey);assert.equal(queued.queued,true);
  assert.deepEqual(await call('POST',`/delivery/notifications/${notification.id}/retry`,body,failed.version,200,admin.accessToken,retryKey),queued);
  await call('POST',`/delivery/notifications/${notification.id}/retry`,body,failed.version,412);
  const confirmed=await poll(async()=>{const d=await get(`/delivery/notifications/${notification.id}`);return d.push.find(p=>p.id===job.id)?.state==='SENT'?d:false;},'retried provider receipt');assert.equal(confirmed.version,failed.version+1);assert.equal(confirmed.retryHistory.length,1);
  assert.equal(pushMessages.filter(p=>p.to===pushToken&&p.data.notificationId===notification.id).length,2);
  await call('POST',`/delivery/notifications/${notification.id}/retry`,body,confirmed.version,409);
  await call('POST',`/delivery/notifications/${notification.id}/retry`,{...body,deliveryId:key()},confirmed.version,404);
  await call('GET',`/delivery/notifications/${notification.id}`,undefined,undefined,403,parent.accessToken);
  await call('GET',`/delivery/notifications/${notification.id}`,undefined,undefined,404,accounts.accessToken);
  assert.ok((await get('/delivery/notifications?resourceId='+c.id+'&failedOnly=false')).items.some(x=>x.id===notification.id));
  passed('delivery retries are scoped, versioned and duplicate safe, with no replay of confirmed sends or exposure of message content and device tokens');

  const notice=await call('POST','/notices',{title:'Reviewed test school notice',body:'A synthetic school notice for the staff delivery summary.',scope:'SCHOOL'});
  await call('POST',`/notices/${notice.id}/publish`,undefined,notice.version);
  const summary=await poll(async()=>{const x=await get(`/notices/${notice.id}/delivery-summary`);return x.inApp.recipients>0?x:false;},'notice recipient summary');assert.equal(summary.providerReceiptIsReadConfirmation,false);assert.ok(summary.inApp.recipients>=2);
  await call('GET',`/notices/${notice.id}/delivery-summary`,undefined,undefined,403,parent.accessToken);
  await call('GET',`/notices/${notice.id}/delivery-summary`,undefined,undefined,403,accounts.accessToken);
  const foreign=await bootstrap('Delivery console isolation');await activate(foreign.slip);const other=await login(foreign.slip.loginId);await choose(other,foreign.schoolId,'PRINCIPAL');
  await call('GET','/delivery/notifications',undefined,undefined,404,other.accessToken);
  const dead=(await get(`/delivery/notifications?category=CASES&failedOnly=true`)).items;
  for(const n of dead.slice(0,10)){
    const detail=await get(`/delivery/notifications/${n.id}`);const invalid=detail.push.find(p=>p.errorCode==='DeviceNotRegistered');
    if(invalid){await call('POST',`/delivery/notifications/${n.id}/retry`,{channel:'PUSH',deliveryId:invalid.id,reason:'A rejected device cannot be re-enabled by a delivery retry'},detail.version,409);break;}
  }
  passed('notice summaries distinguish in-app recipients/read state from provider delivery; staff consoles cannot cross schools or finance permissions');
}
