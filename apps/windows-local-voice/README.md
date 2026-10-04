# Windows Local Japanese Voice Dock App

Windows上の日本語音声認識 → localhostのQwen → Haruka音声合成 → Stack-chan USB CDC v2をつなぐPC dock appです。
既存の[Codex voice dock app](../codex-voice/README.md)とは別の起動モードです。Codex app-server、WebRTC、アカウント、APIキー、課金サービスは使用しません。

## 必要な環境

- Windows、Node.js 22.12以上、Python 3と既存の`pyserial`
- Windows PowerShell 5.1 / `System.Speech`
- インストール済みの日本語音声認識エンジンと`Microsoft Haruka Desktop`
- 既存の公式llama.cpp `llama-server.exe`、検証済みの対象GGUF
- 音声を提供するUSB CDC v2 firmware。マイク、スピーカー、credit、16/24 kHz出力、stream ID capabilityが必要です。EVENT capabilityは不要です。

このappはソフトウェア・モデルをダウンロードしません。Nodeの追加パッケージはありません。サーバーの自動起動はCPU推論、context 2048、8 threads、`--jinja`、`enable_thinking=false`、loopback bindです。
正常稼働中の対象QwenサーバーがあればモデルIDを確認して再利用し、終了時にも停止しません。自分が起動したサーバーだけを終了します。

## 設定

```powershell
Set-Location apps\windows-local-voice
New-Item -ItemType Directory -Force .local | Out-Null
Copy-Item config.example.json .local\config.json
```

`.local/config.json`の`modelPath`と`runtimePath`に既存ファイルの絶対パス、`runtimeSha256`に確認済み実行ファイルのSHA256を設定します。
雛形のポート名とSHA256はプレースホルダーです。公式配布元と使用する既存実行ファイルを確認し、`Get-FileHash -Algorithm SHA256`で得た値を個別設定に記入してください。
`device.port`と`device.serialNumber`は使用する機体を明示指定します。VID `303A` / PID `1001`とUSB serialを照合し、不一致なら開きません。別のポートや機体への自動フォールバックはありません。
`endpoint`には`http://127.0.0.1:8081`などloopback HTTPだけを指定できます。設定・実行結果は`.local`に置き、Gitでは追跡しません。

対象モデルは[Qwen3.5-0.8B-Japanese-SFT-v2 Q4_K_M](https://huggingface.co/Takenoko12345678/Qwen3.5-0.8B-Japanese-SFT-v2-GGUF/tree/c74a009e7ed4528e3bf0a64df6bfe3db5d53ade7)です。
GGUF SHA256は`2a001df6f095274b093a74ca063dbeb29e6dd69d66233c742e02206ced4a4e63`で固定し、起動前に照合します。
作者のモデルライセンスはApache-2.0です。作者READMEに記載された学習データの帰属・ライセンスも維持してください。このappにはGGUFを含めません。

検証機体の既存モデル／runtimeとUSB識別を指す個別設定は`.local/config.json`に置きます。この個別設定はコミットやPRに含めません。元のPoCは変更しません。

## 起動と操作

リポジトリ直下の`Start-Local-Voice.cmd`をダブルクリックするか、app内で次を実行します。

```powershell
npm start
# 設定ファイルを明示する場合:
node src\cli.mjs --config C:\path\to\config.json
```

1. 「準備完了」「Enter: 3秒録音して返答」の表示を待ちます。起動だけでは録音・再生を始めません。
2. Enterを押し、「● 録音中（3秒）」が出てから「こんにちは」など短く話します。表示は実機の`MIC_STARTED`通知後に出します。
3. 「認識」「返答」「再生しています」が表示され、Stack-chanから返答します。音量はfirmware側の設定を使います。
4. 再びEnterで次の発話、`q`＋Enterで終了します。録音・認識・生成・再生中の停止はCtrl+Cです。「終了しました」を待ってウィンドウを閉じます。

起動中の直近3往復だけをLLMの会話に使い、終了すると履歴を破棄します。再生完了まで到達しなかった発話は会話履歴に追加しません。
入力・返答の一時WAVは通常終了とCtrl+Cで削除します。直近の認識文、返答、結果は`.local/last-user-trial.json`にローカル保存します。
`MIC_STOPPED`を待ち、必要なら同じstream IDの停止を500ms間隔で再送します。再生中断は`SPEAKER_ABORT`を送り、COMポートを閉じます。全二重の音声割込みは実装しません。

PCの「再生完了」はprotocol/driverの完了通知です。実際に音が聞こえるかは使用する機体で確認してください。
現在の音声専用PoC firmwareでは頭部gestureやサーボ、表情、Codexの承認UIを使いません。ファームウェアを書き換える処理は含みません。

## 検証

```powershell
npm test
npm run check:host
```

`npm test`はUSBを開かず、wire vector、USB識別、停止／中断、SSE、会話履歴、子プロセスの回収を検証します。モデルも不要です。
`check:host`は設定・モデル/runtimeハッシュ、日本語ASR/Haruka、対象LLMサーバーの起動／healthを確認します。USBを開かず録音・再生しません。検証で起動したサーバーだけを停止します。

この経路の単体PoCはユーザーによる実機動作確認済みです。それは機能確認であり、発話精度や遅延のベンチマークではありません。
小型モデルの記憶・応答品質には制約があります。dockへの移植後の実機確認は別途、上の明示操作で実施してください。

## 構造

`src/cli.mjs`が操作、`src/session.mjs`がASR/LLM/TTSとプロセスの所有権を管理します。
Python側の音声transportは[`contracts/usb-cdc-v2`](../../contracts/usb-cdc-v2)の共通wire定義とvectorに従う独立実装です。
CodexアプリとAndroidアプリのruntimeには依存せず、Android AgentsA1処理を流用しません。
