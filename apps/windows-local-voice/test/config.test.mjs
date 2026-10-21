import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,unlink,rmdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {loadConfig,parseOptions,validateConfig} from '../src/config.mjs';
import {fixtureConfig} from './fixtures.mjs';
function rawConfig(){const {stateDirectory,...config}=fixtureConfig();return config;}
test('config rejects external endpoints, credentials and URL paths',()=>{
  for(const endpoint of ['https://127.0.0.1:8081','http://example.com:8081','http://user:secret@127.0.0.1:8081','http://127.0.0.1:8081/v1','http://127.0.0.1:8081/?key=x'])assert.throws(()=>validateConfig({...rawConfig(),endpoint}),/loopback/);
  assert.equal(validateConfig({...rawConfig(),endpoint:'http://[::1]:8081'}).endpoint,'http://[::1]:8081');
});
test('config requires explicit device identity, verified runtime digest and target model',()=>{
  assert.throws(()=>validateConfig({...rawConfig(),device:{port:'COM12'}}),/serialNumber/);
  assert.throws(()=>validateConfig({...rawConfig(),runtimeSha256:'bad'}),/64 hex/);
  assert.throws(()=>validateConfig({...rawConfig(),modelPath:path.resolve('other.gguf')}),/Qwen/);
  assert.throws(()=>validateConfig({...rawConfig(),autoStart:true}),/Unknown config/);
});
test('Windows UTF-8 BOM configuration loads without modifying it',async()=>{
  const directory=await mkdtemp(path.join(tmpdir(),'dock-config-test-'));
  try{
    const file=path.join(directory,'config.json');await writeFile(file,'\uFEFF'+JSON.stringify(rawConfig()));
    assert.equal((await loadConfig(file)).device.port,'COM12');
  }finally{await unlink(path.join(directory,'config.json')).catch(error=>{if(error.code!=='ENOENT')throw error;});await rmdir(directory);}
});
test('CLI has no automatic microphone start option',()=>{
  assert.deepEqual(parseOptions(['--check-host']).hostOnly,true);
  assert.throws(()=>parseOptions(['--start-immediately']),/Unknown option/);
  assert.throws(()=>parseOptions(['--config']),/Unknown option/);
});
