import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
test('Python wire, identity, stop and cancellation contracts',{timeout:10000},()=>{
  const result=spawnSync(process.platform==='win32'?'python':'python3',['-m','unittest','discover','-s','test','-p','test_*.py'],{cwd:fileURLToPath(new URL('..',import.meta.url)),encoding:'utf8',windowsHide:true});
  assert.equal(result.status,0,result.stderr||result.error?.message);
});
