const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../install/index.html'),'utf8');
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
async function render(reply,{status=200,networkError=false,pathname='/'}={}){
 const nodes=Object.fromEntries(['status','error','download','retry','open'].map(id=>['#'+id,{textContent:'',hidden:true,href:''}]));
 const requests=[];
 const context={window:{BUKKU_CONFIG:{apiBase:'https://amulet-api.onrender.com'}},document:{querySelector:s=>nodes[s]},
   URL,TypeError,AbortController,setTimeout,clearTimeout,location:{pathname,host:'gifts.example.com'},
   fetch:async(url,options)=>{requests.push({url:url.toString(),options});if(networkError)throw new TypeError('Failed to fetch');return{ok:status>=200&&status<300,status,json:async()=>reply};}};
 vm.runInNewContext(script,context);await new Promise(setImmediate);return{nodes,requests};
}
(async()=>{
 let result=await render({apk_available:false,download_url:null});
 assert.equal(result.nodes['#status'].textContent,'앱 설치 파일을 준비 중이에요.');assert.equal(result.nodes['#download'].hidden,true);
 result=await render({apk_available:true,download_url:'https://amulet-api.onrender.com/app/download'});
 assert.equal(result.nodes['#download'].hidden,false);assert.equal(result.nodes['#download'].href,'https://amulet-api.onrender.com/app/download');
 result=await render({apk_available:true,download_url:'https://evil.example/app/download'});assert.equal(result.nodes['#download'].hidden,true);assert.equal(result.nodes['#retry'].hidden,false);
 result=await render(null,{status:404});assert.equal(result.nodes['#error'].textContent,'선물 링크를 확인해주세요.');
 result=await render(null,{networkError:true});assert.equal(result.nodes['#error'].textContent,'인터넷 연결을 확인하고 잠시 후 다시 시도해주세요.');
 const token='A'.repeat(43);result=await render({apk_available:false,download_url:null},{pathname:'/share/'+token});
 assert.equal(result.requests.length,1);assert.equal(result.requests[0].url,'https://amulet-api.onrender.com/share/'+token);
 assert.equal(result.requests[0].options.credentials,'omit');assert.equal(result.nodes['#open'].hidden,false);
 assert.ok(result.nodes['#open'].href.includes('/share/'+token));assert.equal(result.requests.some(r=>r.url.includes('/claim')),false);
 console.log('PASS: 6 installation-page cases (mock responses, not deployed-domain E2E).');
})().catch(error=>{console.error(error);process.exitCode=1;});
