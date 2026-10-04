import { createInterface } from 'node:readline/promises';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { VoiceSession } from './session.mjs';
import { loadConfig,parseOptions } from './config.mjs';

export async function main(args=process.argv.slice(2)) {
  const options=parseOptions(args);
  if(options.help){console.log('Windows ローカル日本語LLMモード\nnode src/cli.mjs [--config <JSON>] [--check-host]\n通常はEnterで3秒録音、q+Enter/Ctrl+Cで終了。--check-hostはUSBを開かずホストだけ検証します。');return;}
  const config=await loadConfig(options.configPath);
  const session=new VoiceSession(config);let readline;
  const stop=()=>{if(!session.signal.aborted)console.log('\n停止しています。録音・再生と接続を終了します…');session.cancel();};
  process.on('SIGINT',stop);process.on('SIGTERM',stop);
  try {
    console.log('stack-chan-dock / Windows ローカル日本語LLMモード');
    await session.prepare({hostOnly:options.hostOnly});
    if(options.hostOnly){console.log('ホスト検証だけで終了します。録音・再生・USB接続は開始していません。');return;}
    readline=createInterface({input:process.stdin,output:process.stdout});
    readline.on('SIGINT',stop);
    console.log('例: 「こんにちは」「元気ですか」「短く自己紹介して」');
    console.log('「録音中」の表示後に話してください。この起動中の直近3往復を会話に使います。');
    console.log('録音はEnterを押したときだけ始まります。終了は q + Enter または Ctrl+C。');
    while(!session.signal.aborted){
      const answer=await readline.question('\nEnter: 3秒録音して返答 / q: 終了 > ',{signal:session.signal});
      if(answer.trim().toLowerCase()==='q')break;
      if(answer.trim())continue;
      try{await session.turn();}
      catch(error){if(session.signal.aborted)break;console.log('試行できませんでした: '+error.message+'\n短い言葉で再試行してください。');}
    }
  }catch(error){
    if(!session.signal.aborted){console.error('準備できませんでした: '+error.message);process.exitCode=1;}
  }finally {
    readline?.close();await session.close();
    session.report.preparationStatus=session.report.health==='ok'?'passed':'failed';
    await writeFile(path.join(config.stateDirectory,'launcher-preparation.json'),JSON.stringify(session.report,null,2)+'\n');
    process.removeListener('SIGINT',stop);process.removeListener('SIGTERM',stop);
    console.log('終了しました。'+(session.report.serverOwned?'このランチャーが起動したLLMサーバーを停止しました。':'既存LLMサーバーは停止していません。'));
  }
}
if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  await main().catch(error=>{console.error('起動できませんでした: '+error.message);process.exitCode=1;});
}
