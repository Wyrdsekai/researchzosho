# Japanese (ja) glossary

`chrome/ja.json` is the reference file, written before this glossary. The words below are the ones it already uses, taken
from it, and the terms chosen since; it is not a complete list of the file. A new sentence uses these words, and a new
term is added here when it is chosen.

## Form of address and style

- です・ます throughout. "You" is あなた where it has to be said, and mostly left out.
- A button or a menu item is a short verb phrase in the plain form or a noun: 試す, 読者にも使わせる, {0}に翻訳する, 設定.
- A fragment the program puts inside another sentence or joins with others (a reason after "because", one clause of a
  list joined with "; ") is a plain-form clause or ends in a noun: …できなかった, 除外の対象外.
- A reason the program fills in is put after 理由： ("…できませんでした。理由：{0}。"), so that any reason, or "?", reads.
- What the chat asks the person to allow (an act the model asked for) is a noun phrase ending in こと, because the program
  puts it after モデルからの依頼：.
- Punctuation is full width: 、。：（）「」. A half-width space stands between Japanese and a word in Latin letters, a
  command or a placeholder that a Latin name or an address fills ({0} のモデルサーバー, Docker ネットワーク); a placeholder that
  Japanese words fill closes up ({0}に拠点のある, with {0} = 中国、香港とマカオ); a number with its counter or unit has none
  ({0}件, {0}秒, {0}分), except before a Latin unit (約 1.2 GB).
- A placeholder that a language name fills takes no space: {0}への翻訳, {0}に翻訳する.

## Counts

Japanese has one form. The first text (exactly 1) keeps the 1 as the English has it ("1件は…"); the second has {0}.

## One word per thing (as `ja.json` says it)

| English | Japanese |
|---|---|
| library | ライブラリ |
| the Librarian | 司書 |
| report | レポート |
| research (the act); a research run | 調査 |
| run (of a job, of the program) | 実行 |
| source (cited) / source (mined) | 出典 / 情報源 |
| claim | 主張 |
| subject | 主題 |
| shelves | 書架 (棚 in a few older sentences) |
| setting / the setting {0} | 設定 / 設定 {0} |
| the person who keeps the library | 管理者 |
| those who may write | 書ける人; the translation notices of a report or a dossier say 書き込みできる人, as the sentences they replaced did |
| reader | 読者 |
| machine | マシン |
| entry (a saved model) | エントリ |
| key | キー |
| install | 入れる |
| fetch | 取得する |
| an earlier version | 以前の版 |
| model server | モデルサーバー |
| hosted model | ホスト型のモデル |
| provider (of a hosted model) | 提供元 |
| host | ホスト |
| search engine | 検索エンジン |
| search by meaning | 意味による検索 |
| answer (a server, a model) | 応答する |
| address (web) | アドレス (住所 is a home's) |
| tool | ツール |
| command | コマンド |
| service | サービス |
| the report is shown as written | レポートは原文のまま表示しています |

## 0.8.1: the places a library avoids, and the model server with no way out

| English | Japanese |
|---|---|
| place (a country code the setting names: CN, HK…) | 国・地域 |
| keep away from / avoid; the places this library avoids | 避ける; このライブラリが避ける国・地域 |
| based or runs in (a provider) | 拠点がある、または稼働している; in a short clause: 拠点または稼働先の国・地域 |
| owned by a company based in {0}; its owner's place | {0} に本拠を置く会社が所有する; 所有会社の国・地域 |
| states no place | 国・地域の記載がない |
| left out (a provider) | 除外する; not left out: 除外の対象外 |
| serve (a provider serves a model) | 扱う; "let them serve it": 使えるようにする |
| allow (providers) | 認める |
| router (Hugging Face's) | ルーター |
| request (one call to a router) | 要求 |
| no way out to the network / can reach the network | 外部と通信できない / 外部と通信できる |
| set up (the model server) with no way out / able to reach the network | 外部と通信できない形で用意する / 外部と通信できる形で用意する; "set up the way earlier versions did": 以前の版と同じ形で用意する |
| set up again | 用意し直す |
| the earlier way (stepped back to it) | 以前の形 |
| an outside address | 外部のアドレス |
| Docker network / container / proxy / route / address range | Docker ネットワーク / コンテナ / プロキシ / 経路 / アドレス範囲 |
| embeddings server / embeddings model | 埋め込みサーバー / 埋め込みモデル |
| start script | 起動スクリプト |
| open / closed (the values of model_network) | stay as written: 設定 {0} は open です |
| an MCP server's answers give addresses (the keeper says it finds things outside) | 調査は、このサーバーの答えに出てくるアドレスを開けます; the button: 見つけたアドレスを調査で開く; the other: 答えのアドレスを開かない |
| the eight places of the unset list (CN HK MO CU IR KP RU VE), as the language ordinarily names them | 中国、香港、マカオ、キューバ、イラン、北朝鮮、ロシア、ベネズエラ; a list of them is joined as the program joins every list (中国、香港とマカオ); any other place by the locale's name for its code |
| the line of a model on OpenRouter | whole sentences, each with its 。 (提供元は6件です。…除外しています（…）。) |
