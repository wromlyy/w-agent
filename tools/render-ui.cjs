/** Render the packaged UI in Chrome with example data. No real accounts or keys. */
const {chromium}=require('playwright-core');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
(async()=>{
 const root=path.resolve('app/src/main/assets');
 const server=http.createServer((req,res)=>{
  const target=path.resolve(root,'.'+new URL(req.url,'http://localhost').pathname.replace(/\/$/,'/index.html'));
  if(!target.startsWith(root+path.sep)){res.writeHead(403).end();return;}
  try{res.setHeader('Content-Type',target.endsWith('.css')?'text/css':target.endsWith('.js')?'application/javascript':target.endsWith('.svg')?'image/svg+xml':'text/html');res.end(fs.readFileSync(target));}catch(e){res.writeHead(404).end();}
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox']});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{
   let settings={profiles:[{id:'demo',name:'Relay',preset:'relay',type:'compatible',base:'https://codex-relay.codes/v1',model:'kimi-k2',key:''}],activeProfile:'demo',services:[],skills:[],history:[],theme:'light',vision:false,maxSteps:30};
   window.WNative={post(raw){const r=JSON.parse(raw);let value;
    if(r.op==='init')value={settings,accessibility:true,run:{}};
    else if(r.op==='state')value={accessibility:true,run:{}};
    else if(r.op==='save'){settings=r.args;value=true;}
    else if(r.op==='models')value=['kimi-k2','deepseek-chat','qwen3-max','gemini-2.5-flash','claude-sonnet-4-5','gpt-4.1'];
    else if(r.op==='apps')value=[];else value=true;
    queueMicrotask(()=>window.nativeReply(r.id,value,null));
   }};
  });
  fs.mkdirSync('.private/previews',{recursive:true});
  await page.goto(`http://127.0.0.1:${server.address().port}/`);await page.locator('.intro h1').waitFor();
  await page.screenshot({path:'.private/previews/01-chat-light.png'});
  await page.locator('nav [data-page=connections]').click();await page.locator('.provider-gallery').waitFor();
  await page.screenshot({path:'.private/previews/02-connections.png'});
  await page.locator('.page-top [data-action=addProfile]').click();await page.locator('[data-action=preset][data-preset=deepseek]').click();
  await page.screenshot({path:'.private/previews/03-model-setup.png'});
  await page.locator('[data-action=closeModal]').click();await page.locator('header [data-page=settings]').click();
  await page.locator('[data-setting=theme]').check();await page.locator('nav [data-page=chat]').click();
  await page.screenshot({path:'.private/previews/04-chat-dark.png'});
  assert.deepEqual(errors,[],'Browser JavaScript errors');
  const brokenImages=await page.locator('img').evaluateAll(images=>images.filter(i=>!i.complete||i.naturalWidth===0).map(i=>i.src));
  assert.deepEqual(brokenImages,[],'Broken logos');
  console.log('Chrome renders completed; screenshots in .private/previews');
 }finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exit(1);});
