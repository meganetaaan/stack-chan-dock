param(
    [Parameter(Mandatory=$true)][ValidateSet('asr','tts','check')][string]$Mode,
    [string]$InputPath,
    [string]$OutputPath
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.Speech
if ($Mode -eq 'check') {
    $engine=New-Object System.Speech.Recognition.SpeechRecognitionEngine([Globalization.CultureInfo]::GetCultureInfo('ja-JP'))
    $synth=New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        $synth.SelectVoice('Microsoft Haruka Desktop')
        [ordered]@{recognizer=$engine.RecognizerInfo.Name;culture=$engine.RecognizerInfo.Culture.Name;voice=$synth.Voice.Name;audioStarted=$false;networkUsed=$false} | ConvertTo-Json -Compress
    } finally { $engine.Dispose();$synth.Dispose() }
} elseif ($Mode -eq 'asr') {
    $engine=New-Object System.Speech.Recognition.SpeechRecognitionEngine([Globalization.CultureInfo]::GetCultureInfo('ja-JP'))
    try {
        $engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
        $engine.SetInputToWaveFile((Resolve-Path -LiteralPath $InputPath).Path)
        $result=$engine.Recognize([TimeSpan]::FromSeconds(10))
        if (-not $result) { throw 'No local recognition result' }
        [ordered]@{text=$result.Text;confidence=$result.Confidence;scope='First recognized utterance from supplied WAV';networkUsed=$false} | ConvertTo-Json -Compress
    } finally { $engine.Dispose() }
} else {
    if (-not $OutputPath) { throw 'OutputPath required for TTS' }
    $text=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $InputPath).Path,[Text.Encoding]::UTF8)
    if (-not $text.Trim() -or $text.Length -gt 100) { throw 'TTS requires 1-100 characters' }
    $synth=New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        $synth.SelectVoice('Microsoft Haruka Desktop')
        $format=New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono)
        $synth.SetOutputToWaveFile($OutputPath,$format)
        $synth.Speak($text)
        $synth.SetOutputToNull()
        [ordered]@{voice='Microsoft Haruka Desktop';sampleRate=16000;channels=1;bitsPerSample=16;networkUsed=$false} | ConvertTo-Json -Compress
    } finally { $synth.Dispose() }
}
