const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {JSDOM}=require('jsdom');
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
const script=fs.readFileSync('app/src/main/assets/app.js','utf8');
const pause=()=>new Promise(r=>setImmediate(r));

async function boot(seed={}){
 const dom=new JSDOM(html,{url:'https://app.wagent.local/',runScripts:'outside-only'});
 const w=dom.window,calls=[];
 let settings={profiles:[],services:[],skills:[],history:[],activeProfile:'',theme:'light',vision:false,maxSteps:30,...seed.settings},run=seed.run||{};
 w.WNative={post(raw){const req=JSON.parse(raw);calls.push(req);queueMicrotask(()=>{
  let result;
  switch(req.op){
   case 'shared':result=seed.shared||{files:[],text:''};seed.shared=null;break;
   case 'terminalList':result=[];break;
   case 'projectRead':result='const value = 1;\n';break;
   case 'projectWrite':result={verified:true,checkpoint:'c1'};break;
   case 'init':result={settings,run,accessibility:true};break;
   case 'state':result={run,accessibility:true};break;
   case 'save':settings=JSON.parse(JSON.stringify(req.args));result=true;break;
   case 'models':result=['kimi-k2','deepseek-chat','qwen3-test'];break;
   case 'freeModels':result=[{id:'openrouter/free',name:'Free router',tools:true,vision:true,context:100000},{id:'test/chat:free',name:'Chat model',tools:false,vision:false,context:32000}];break;
   case 'authBegin':result=true;break;
   case 'authComplete':settings.profiles.push({id:'auth-profile',name:'OpenRouter',model:'openrouter/free',freeOnly:true,preset:'openrouter'});settings.activeProfile='auth-profile';result=true;break;
   case 'apps':result=[{name:'YouTube',package:'com.google.android.youtube'},{name:'TikTok',package:'com.zhiliaoapp.musically'}];break;
   case 'run':run={task:req.args.task,running:true,status:'running',events:[],step:1,maxSteps:30};result=true;break;
   case 'newChat':run={};result=true;break;
   case 'stop':run={...run,running:false,status:'stopped'};result=true;break;
   case 'pick':result=[{id:'file1',name:'<img src=x>.md',mime:'text/markdown',text:'# File',size:7}];break;
   case 'testService':result='{}';break;
   case 'folderPick':settings.folders=[{id:'folder-1',name:'Documents',uri:'content://test/tree'}];result=settings.folders[0];break;
   case 'fileList':result=[{name:'notes.txt',path:'notes.txt',directory:false,size:12}];break;
   case 'fileAttach':result={id:'attachment-1',name:'notes.txt',mime:'text/plain',size:12,text:'device file'};break;
   case 'fileOutput':result={id:'output-1',name:req.args.name,mime:'text/markdown',size:req.args.content.length};settings.outputs=[result];break;
   default:result=true;
  }
  w.nativeReply(req.id,JSON.parse(JSON.stringify(result)),null);
 });}};
 for(const file of ['marked.js','purify.js','highlight.js'])w.eval(fs.readFileSync('app/src/main/assets/vendor/'+file,'utf8'));
 w.eval(script);await pause();
 return {w,dom,calls,get settings(){return settings;},async click(selector){const e=w.document.querySelector(selector);assert.ok(e,selector);e.click();await pause();},set(name,value){w.document.querySelector(`[name=${name}]`).value=value;},async submit(){w.document.querySelector('#editor').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));await pause();}};
}
test('provider setup, remote model catalog, activation and task stop',async()=>{
 const a=await boot();try{
  await a.click('[data-action=chooseModel]');
  assert.ok(a.w.document.querySelector('[data-preset=kimi]'));
  await a.click('[data-action=preset][data-preset=kimi]');
  assert.equal(a.w.document.querySelector('[name=base]').value,'https://api.moonshot.ai/v1');
  a.set('key','test-api-key');await a.click('[data-action=loadModels]');
  assert.equal(a.w.document.querySelectorAll('[data-action=modelName]').length,3);
  await a.click('[data-model="kimi-k2"]');await a.submit();
  assert.equal(a.settings.profiles[0].model,'kimi-k2');
  assert.equal(a.settings.profiles[0].preset,'kimi');
  assert.equal(a.settings.activeProfile,a.settings.profiles[0].id);
  const p=a.w.document.querySelector('#prompt');p.value='Открой YouTube';p.dispatchEvent(new a.w.Event('input',{bubbles:true}));
  await a.click('[data-action=send]');assert.ok(a.w.document.querySelector('[data-action=stop]'));
  assert.equal(a.calls.find(c=>c.op==='run').args.task,'Открой YouTube');
  await a.click('[data-action=stop]');assert.ok(a.calls.some(c=>c.op==='stop'));
 }finally{a.dom.window.close();}
});
test('skills, GitHub service, app selection and file content escaping',async()=>{
 const a=await boot();try{
  await a.click('[data-page=skills]');await a.click('[data-action=importSkill]');
  assert.equal(a.w.document.querySelector('[name=content]').value,'# File');
  a.set('name','Мой навык');await a.submit();assert.equal(a.settings.skills.length,1);
  await a.click('[data-page=connections]');await a.click('[data-tab=services]');await a.click('[data-action=githubService]');
  a.set('token','github-test-token');await a.submit();assert.equal(a.settings.services[0].url,'https://api.github.com');
  assert.equal(a.settings.services[0].enabled,true);
  await a.click('[data-page=apps]');await pause();await a.click('[data-id="com.google.android.youtube"]');
  assert.match(a.w.document.querySelector('#prompt').value,/YouTube/);
  await a.click('[data-action=attach]');assert.equal(a.w.document.querySelector('.file-chip span').textContent,'<img src=x>.md');
  assert.equal(a.w.document.querySelectorAll('.file-chip img').length,0);
  await a.click('[data-action=removeFile]');assert.equal(a.w.document.querySelectorAll('.file-chip').length,0);
 }finally{a.dom.window.close();}
});
test('settings persist and profile validation rejects unsafe URLs',async()=>{
 const a=await boot();try{
  await a.click('[data-page=settings]');const toggle=a.w.document.querySelector('[data-setting=theme]');toggle.checked=true;toggle.dispatchEvent(new a.w.Event('change',{bubbles:true}));await pause();
  assert.equal(a.settings.theme,'dark');assert.equal(a.w.document.documentElement.dataset.theme,'dark');
  await a.click('[data-page=connections]');await a.click('[data-action=addProfile]');a.set('base','http://unsafe.example');a.set('model','model');await a.submit();assert.match(a.w.document.querySelector('#form-error').textContent,/https/);assert.equal(a.settings.profiles.length,0);
 }finally{a.dom.window.close();}
});
test('free catalog selects chat-only mode and retains zero-price guard when editing',async()=>{
 const a=await boot();try{
  await a.click('[data-action=freeTab]');await pause();
  assert.equal(a.w.document.querySelectorAll('[data-action=freePick]').length,2);
  await a.click('[data-action=freePick][data-id="test/chat:free"]');
  assert.equal(a.w.document.querySelector('[name=mode]').value,'chat');
  a.set('key','test-free-key');await a.submit();
  assert.equal(a.settings.profiles[0].freeOnly,true);assert.equal(a.settings.profiles[0].mode,'chat');
  await a.click('[data-tab=models]');await a.click('[data-action=editProfile]');await a.submit();
  assert.equal(a.settings.profiles[0].freeOnly,true);
 }finally{a.dom.window.close();}
});
test('browser accounts never claim to be API connections and OpenRouter code finishes setup',async()=>{
 const a=await boot();try{
  await a.click('[data-page=connections]');await a.click('[data-tab=accounts]');await a.click('[data-action=accountOpen][data-id=chatgpt]');
  assert.equal(a.calls.find(c=>c.op==='openAccount').args.account,'chatgpt');assert.equal(a.settings.profiles.length,0);
  await a.click('[data-action=authBegin]');
  a.w.document.querySelector('#auth-code').value='single-use-test-code';await a.click('[data-action=authComplete]');await pause();
  assert.equal(a.settings.activeProfile,'auth-profile');
  assert.ok(a.calls.some(c=>c.op==='authComplete'&&c.args.code==='single-use-test-code'));
  assert.equal(a.w.document.querySelector('#auth-code'),null);
 }finally{a.dom.window.close();}
});
test('restricted settings help opens app settings without claiming to grant permissions',async()=>{
 const a=await boot();try{
  await a.click('[data-action=accessibility]');assert.match(a.w.document.querySelector('.modal').textContent,/Разрешить ограниченные настройки/);
  await a.click('[data-action=appSettings]');assert.ok(a.calls.some(c=>c.op==='appSettings'));
  await a.click('[data-action=openAccessibility]');assert.ok(a.calls.some(c=>c.op==='accessibility'));
 }finally{a.dom.window.close();}
});
test('continuous chat keeps previous messages and copies exact assistant text',async()=>{
 const one={id:'turn1',chatId:'chat1',task:'First task',status:'done',events:[{kind:'assistant',text:'First reply\nwith code <example>'}]};
 const two={id:'turn2',chatId:'chat1',task:'Second task',status:'done',running:false,events:[{kind:'assistant',text:'Second reply'}]};
 const a=await boot({settings:{history:[one,two]},run:two});try{
  assert.equal(a.w.document.querySelectorAll('.chat-turn').length,2);
  assert.match(a.w.document.querySelector('.message-text').textContent,/First reply/);assert.match(a.w.document.querySelector('.message-text').textContent,/with code <example>/);
  await a.click('[data-action=copyMessage][data-run=turn1]');assert.equal(a.calls.find(c=>c.op==='copyText').args.text,one.events[0].text);
  await a.click('[data-action=saveMessage][data-run=turn2]');assert.equal(a.calls.find(c=>c.op==='fileOutput').args.content,'Second reply');
  assert.ok(a.w.document.querySelector('[data-action=saveOutput]'));assert.ok(a.w.document.querySelector('[data-action=shareOutput]'));
 }finally{a.dom.window.close();}
});
test('reasoning controls retain effort and thinking mode on the selected provider',async()=>{
 const p={id:'p1',name:'OpenRouter',preset:'openrouter',base:'https://openrouter.ai/api/v1',type:'compatible',model:'test-model'};
 const a=await boot({settings:{profiles:[p],activeProfile:'p1'}});try{
  await a.click('[data-action=reasonMenu]');a.set('thinking','on');a.set('effort','xhigh');await a.submit();
  assert.equal(a.settings.profiles[0].effort,'xhigh');assert.equal(a.settings.profiles[0].thinking,'on');assert.match(a.w.document.querySelector('.reasoning-chip').textContent,/Extreme/);
 }finally{a.dom.window.close();}
});
test('device folder selection and browsing attach the selected file to the chat',async()=>{
 const a=await boot();try{
  await a.click('[data-page=files]');await a.click('[data-action=folderPick]');await a.click('[data-action=openFolder]');await pause();
  await a.click('[data-action=fileMenu]');await a.click('[data-action=attachDeviceFile]');
  assert.equal(a.calls.find(c=>c.op==='fileAttach').args.folder,'folder-1');assert.equal(a.calls.find(c=>c.op==='fileAttach').args.path,'notes.txt');
  assert.equal(a.w.document.querySelector('.file-chip span').textContent,'notes.txt');
 }finally{a.dom.window.close();}
});
test('mode switch is visible in composer and planning edits are sent to the native gate',async()=>{
 const p={id:'p',name:'Model',model:'test',preset:'openrouter'};
 const a=await boot({settings:{profiles:[p],activeProfile:'p'}});try{
  await a.click('[data-action=modeMenu]');await a.click('[data-action=setMode][data-id=chat]');assert.equal(a.settings.agentMode,'chat');assert.match(a.w.document.querySelector('.mode-chip').textContent,/Чат/);
 }finally{a.dom.window.close();}
 const b=await boot({settings:{profiles:[p],activeProfile:'p'},run:{id:'plan',chatId:'chat',task:'Do work',running:true,status:'awaiting_plan',plan:'Read then edit',events:[]}});try{
  b.w.document.querySelector('#approved-plan').value='Read, test, then edit';await b.click('[data-action=approvePlan]');assert.equal(b.calls.find(c=>c.op==='planApprove').args.plan,'Read, test, then edit');
 }finally{b.dom.window.close();}
});
test('Markdown sanitizes active content and copies source code blocks exactly',async()=>{
 const text='| A | B |\n|---|---|\n| 1 | 2 |\n\n```js\nconst x = "<tag>";\n```\n\n<img src=x onerror="window.pwned=1">\n[bad](javascript:alert(1))';
 const a=await boot({run:{id:'m',task:'Example',status:'done',events:[{kind:'assistant',text}]}});try{
  assert.ok(a.w.document.querySelector('.message-text table'));assert.equal(a.w.document.querySelectorAll('.message-text img,.message-text script').length,0);assert.equal(a.w.pwned,undefined);assert.equal(a.w.document.querySelector('a')?.getAttribute('href'),null);
  await a.click('[data-action=copyCode]');assert.equal(a.calls.find(c=>c.op==='copyText').args.text,'const x = "<tag>";\n');
 }finally{a.dom.window.close();}
});
test('project editor keeps changes across tabs and submits the complete file for diff confirmation',async()=>{
 const a=await boot({settings:{folders:[{id:'f',name:'Project'}]}});try{
  await a.click('[data-page=files]');await a.click('[data-action=openFolder]');await pause();await a.click('[data-action=fileMenu]');await a.click('[data-action=codeOpen]');
  const editor=a.w.document.querySelector('#code-editor');editor.value='const value = 2;\n';editor.dispatchEvent(new a.w.Event('input',{bubbles:true}));await a.click('[data-action=codeSave]');
  assert.equal(a.calls.find(c=>c.op==='projectWrite').args.content,'const value = 2;\n');assert.equal(a.calls.find(c=>c.op==='projectWrite').args.folder,'f');
 }finally{a.dom.window.close();}
});
test('role selection persists and sidebar reaches the real terminal view',async()=>{
 const roles=[{id:'general',name:'General',tools:['files'],prompt:'Help',effort:'auto'},{id:'reviewer',name:'Reviewer',tools:['project'],prompt:'Read only',readOnly:true,trust:'read_auto',effort:'high'}];
 const a=await boot({settings:{roles}});try{
  await a.click('[data-action=roleMenu]');await a.click('[data-action=roleSelect][data-id=reviewer]');assert.equal(a.settings.activeRole,'reviewer');assert.match(a.w.document.querySelector('.role-chip').textContent,/Reviewer/);
  await a.click('[data-action=menu]');await a.click('.drawer-links [data-page=terminal]');assert.ok(a.w.document.querySelector('#terminal-command'));assert.ok(a.calls.some(c=>c.op==='terminalList'));
 }finally{a.dom.window.close();}
});
test('Android share target prepares a draft and photo attachment without starting an agent',async()=>{
 const photo={id:'photo',name:'photo.jpg',mime:'image/jpeg',data:'dGVzdA==',size:4};
 const a=await boot({shared:{text:'Опиши фото',files:[photo]}});try{
  await pause();assert.equal(a.w.document.querySelector('#prompt').value,'Опиши фото');assert.ok(a.w.document.querySelector('.attachment-thumb'));assert.equal(a.calls.some(c=>c.op==='run'),false);
 }finally{a.dom.window.close();}
});
