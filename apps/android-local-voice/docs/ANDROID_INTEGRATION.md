# Android音声機能の統合

## 統合対象

versionCode 9の評価ソースを既定のdevelopへ統合します。Piper 1.13とVOICEVOXを保持し、分離したPiper Plus 2.0.1、CPU版sanoTTS-jp、Pocket日本語を同じSpeechSynthesizerへ接続しています。会話は既存のUSB credit / START / END / DONE / ABORTとresamplerを使用します。推論からUSB writerへ直接書く経路は追加しません。

スマホ単体の会話、端末内の録音・明示保存・試聴、保存録音からのPocket話者状態作成、選択保持、確認付き録音削除、Pocket標準10音声の明示取得・準備・選択・試聴を含みます。元録音の削除後も作成済みの声を保持します。追加9音声のWAVはAPKに含めず、公式配布元から固定サイズ・SHA-256を確認して端末内で変換します。

Bonsaiや調査中のメモリ判定変更は含みません。1.5GiB以上の空きRAMとOS lowMemory状態の既存チェックを維持します。利用者からはE4Bで起きた準備エラーがE2Bへの変更で解消した報告がありました。この報告は全構成のメモリ安全性やE4B対応を保証する測定ではありません。余裕が足りない場合は既存の軽いLLM設定を使用してください。

## ビルド

JDK 17、Android SDK 37、既存NDK r30を使用します。まず[ネイティブ依存物](../app/libs/README.md)を配置してください。モデルZIPはコンパイルに不要です。ネイティブAAR/SOを含まない新しいcloneから、そのまま全エンジンをビルドすることはできません。特にPocketのai.onnxirodoriクラスはコンパイル時にも必要です。既存の承認済み評価ビルドから、記録されたhashと一致するコード用依存物を用意する前提です。

```sh
./scripts/dev.sh ./gradlew --max-workers=1 \
  -PsanoEvaluation=true -PnativeNdkPath=/path/to/existing/android-ndk-r30 \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 scripts/check_project.py
```

同一パッケージの上書き更新には既存の署名鍵が必要です。リポジトリは署名鍵を配布しません。公式依存物やビルド設定を変更した場合、以前のAPKの検証結果を新しいバイナリへ流用せず、SOの依存関係・namespace分離・16KiBアラインメントを再確認します。

## データと条件

ソース管理にはモデル重み、モデルZIP、辞書バイナリ、録音WAV、派生話者状態、AAR/SO、APK、keystore、認証情報を追加しません。個人用モデル配布リンクも含めません。モデルは利用者の明示操作で別途取得・取り込みし、固定manifestの全バイト・サイズ・hashを検証します。録音や作成した声を外へ送信する処理はありません。

アプリのソースライセンスと、各モデル・音声の利用条件は別です。Pocket日本語の重みと派生ONNX/BOSはCC BY-NC 4.0、コードはMIT、SentencePieceはApache 2.0です。追加の公式WAVや派生状態は再配布せず端末内の非商用評価に使います。VOICEVOX、つくよみちゃん、sano v4のクレジットと条件確認も保持します。詳細は[第三者コンポーネント](../THIRD_PARTY_NOTICES.md)と各エンジン文書を参照してください。

## 確認済みと未検証

統合前の評価正本a4cfc526では、JVM単体テスト235件（失敗・エラー・skipすべて0）、lintエラー0・警告24件、署名付きarm64 debug APK生成を確認しました。PC mockではキャンセル、古い要求の破棄、切替・解放、モデル未準備、入力上限、USB credit/完了/切断の既存経路を検証しています。既存とPocketの計31 native libraryのhash・依存解決・16KiB配置も静的確認しました。公開用整理は個人配布リンクの除去と文書更新に限り、推論・音声処理・メモリ閾値を変更しません。

PC CPU1では公式9音声の実WAVのhash検証、先頭5秒のencode/prefill、状態保存・再読込、全10音声の異なるPCM、同音声の再現とfemale03復帰のバイト一致を確認しました。人の聴感やAndroidの速度・メモリピークを示す結果ではありません。

実機は利用者のE2B解消報告と、文書中に記録された従来経路の確認に限ります。追加全音声の可聴品質・読み精度、LLM/STT同居時のピークRAM、OSの権限/着信/背景動作、全エンジンのロード/切替、実Dock USBでの停止・切断・再接続は引き続き確認が必要です。各機能文書の古いversionCodeや「未インストール」は、その変更を作成した時点の記録です。
