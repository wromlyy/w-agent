/* Corresponding source, patches and build recipes for the bundled binaries. No source is executed. */
const fs=require('fs'),path=require('path'),crypto=require('crypto'),cp=require('child_process');
const recipes=require('../third_party/linux-runtime/source-recipes.json');
const target='.private/linux-source-bundle',hash=(algo,b)=>crypto.createHash(algo).update(b).digest('hex');
async function fetchBytes(url){const r=await fetch(url);if(!r.ok)throw Error(`${r.status}: ${url}`);return Buffer.from(await r.arrayBuffer());}
async function checked(url,file,algorithm,expected,git=false){if(fs.existsSync(file)){const b=fs.readFileSync(file),input=git?Buffer.concat([Buffer.from(`blob ${b.length}\0`),b]):b;if(hash(algorithm,input)===expected)return;}const b=await fetchBytes(url),input=git?Buffer.concat([Buffer.from(`blob ${b.length}\0`),b]):b;if(hash(algorithm,input)!==expected)throw Error(`Source checksum mismatch: ${url}`);fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,b);}
(async()=>{
 fs.mkdirSync(target,{recursive:true});
 // Bounded parallel fetches avoid depending on an API rate limit during CI.
 let cursor=0;await Promise.all(Array.from({length:6},async()=>{while(cursor<recipes.length){const item=recipes[cursor++];await checked(item.url,path.join(target,item.path),'sha1',item.gitSha1,true);}}));
 for(const origin of require('../third_party/linux-runtime/alpine-origins.json')){const dir=path.join(target,'alpine',origin.origin),recipe=fs.readFileSync(path.join(dir,'APKBUILD'),'utf8');
  const sums=recipe.match(/sha512sums="([\s\S]*?)"/);if(!sums){if(origin.origin==='alpine-base')continue;throw Error(`Missing source checksums: ${origin.origin}`);}
  for(const line of sums[1].trim().split('\n')){const match=line.trim().match(/^([a-f0-9]{128})\s+(.+)$/);if(!match)continue;const [,sha,name]=match;if(name.includes('..')||path.isAbsolute(name))throw Error('Unsafe source path');const file=path.join(dir,name);if(fs.existsSync(file)){if(hash('sha512',fs.readFileSync(file))!==sha)throw Error(`Recipe file checksum mismatch: ${file}`);}else await checked(`https://distfiles.alpinelinux.org/distfiles/v3.22/${encodeURIComponent(name)}`,file,'sha512',sha);}
 }
 for(const item of require('../third_party/linux-runtime/source-archives.json'))await checked(item.url,path.join(target,'termux',item.name),'sha256',item.sha256);
 fs.cpSync('third_party/linux-runtime',path.join(target,'manifest'),{recursive:true});fs.copyFileSync('tools/prepare-linux.cjs',path.join(target,'prepare-linux.cjs'));fs.copyFileSync('tools/prepare-linux-sources.cjs',path.join(target,'prepare-linux-sources.cjs'));
 cp.execFileSync('tar',['-czf','W-Agent-Linux-Sources.tar.gz','-C',target,'.']);console.log('Created W-Agent-Linux-Sources.tar.gz: upstream source archives + exact Alpine recipes/configuration/patches + Termux build environment');
})().catch(e=>{console.error(e);process.exit(1)});
