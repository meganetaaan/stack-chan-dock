import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const appRoot=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
export const MODEL_NAME='Qwen3.5-0.8B-Japanese-SFT-v2-Q4_K_M.gguf';
export const MODEL_SHA256='2a001df6f095274b093a74ca063dbeb29e6dd69d66233c742e02206ced4a4e63';
export const defaultConfigPath=path.join(appRoot,'.local','config.json');

export function validateConfig(raw) {
  if(!raw || typeof raw!=='object' || Array.isArray(raw))throw new Error('設定はJSONオブジェクトが必要です。');
  for(const key of Object.keys(raw))if(!['modelPath','runtimePath','runtimeSha256','endpoint','device','python','powershell'].includes(key))throw new Error('Unknown config key: '+key);
  for(const key of ['modelPath','runtimePath','python','powershell'])if(typeof raw[key]!=='string'||!raw[key].trim())throw new Error('Missing config: '+key);
  if(!path.isAbsolute(raw.modelPath)||!path.isAbsolute(raw.runtimePath))throw new Error('モデルとllama-serverには絶対パスを設定してください。');
  if(path.basename(raw.modelPath)!==MODEL_NAME)throw new Error('対象Qwen GGUFのファイル名が一致しません。');
  if(typeof raw.runtimeSha256!=='string'||!/^[a-f0-9]{64}$/i.test(raw.runtimeSha256))throw new Error('runtimeSha256 must contain 64 hex digits');
  const endpoint=new URL(raw.endpoint??'http://127.0.0.1:8081');
  if(endpoint.protocol!=='http:'||!['127.0.0.1','[::1]'].includes(endpoint.hostname)||endpoint.username||endpoint.password||endpoint.pathname!=='/'||endpoint.search||endpoint.hash)throw new Error('LLM endpoint must be loopback HTTP without credentials or a path');
  const port=Number(endpoint.port||80);
  if(port<1024||port>65535)throw new Error('LLM port must be 1024-65535');
  if(!raw.device||typeof raw.device!=='object'||Object.keys(raw.device).some(key=>!['port','serialNumber'].includes(key)))throw new Error('device.port and device.serialNumber are required');
  if(!/^COM[1-9]\d{0,3}$/i.test(raw.device.port??''))throw new Error('対象Windows COMポートを設定してください。');
  if(typeof raw.device.serialNumber!=='string'||!/^[a-z0-9:._-]{1,128}$/i.test(raw.device.serialNumber)||raw.device.serialNumber==='REPLACE_WITH_SELECTED_USB_SERIAL')throw new Error('対象USB serialNumberを設定してください。');
  return Object.freeze({...raw,device:Object.freeze({...raw.device,port:raw.device.port.toUpperCase()}),runtimeSha256:raw.runtimeSha256.toLowerCase(),endpoint:endpoint.origin,stateDirectory:path.join(appRoot,'.local')});
}

export async function loadConfig(file=defaultConfigPath) {
  try{return validateConfig(JSON.parse((await readFile(file,'utf8')).replace(/^\uFEFF/,'')));}
  catch(error){if(error.code==='ENOENT')throw new Error('設定ファイルがありません。config.example.jsonを.local/config.jsonへコピーし、既存モデル・実行ファイル・USB識別を設定してください。');throw error;}
}

export function parseOptions(args) {
  const options={configPath:defaultConfigPath,hostOnly:false,help:false};
  for(let i=0;i<args.length;i++){
    if(args[i]==='--help'||args[i]==='-h')options.help=true;
    else if(args[i]==='--check-host')options.hostOnly=true;
    else if(args[i]==='--config'&&args[i+1]&&!args[i+1].startsWith('--'))options.configPath=path.resolve(args[++i]);
    else throw new Error('Unknown option: '+args[i]);
  }
  return options;
}
