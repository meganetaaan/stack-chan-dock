import path from 'node:path';
import {validateConfig,MODEL_NAME} from '../src/config.mjs';
export function fixtureConfig(overrides={}){
  return {...validateConfig({modelPath:path.resolve('fixtures',MODEL_NAME),runtimePath:path.resolve('fixtures','llama-server.exe'),runtimeSha256:'f'.repeat(64),endpoint:'http://127.0.0.1:8081',device:{port:'COM12',serialNumber:'TEST-SERIAL'},python:process.platform==='win32'?'python':'python3',powershell:'powershell'}),...overrides};
}
