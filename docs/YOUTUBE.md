# YouTube with ResearchZosho

ResearchZosho can research what is on YouTube: which channels make a certain kind of video, whether a
channel still posts, what is said in a video and at which moment, and which channels are like one you
already know. It does this without a YouTube account and without an API key, on your own computer.

**Where you type the commands.** The lines in grey boxes are typed into a terminal (Terminal on a Mac,
PowerShell on Windows, any terminal on Linux), one line at a time, with Enter after each.

## What it reads, and how

YouTube's own interface for programs (the Data API) allows about a hundred searches a day. One research
run can use that up before lunch. So the library reads YouTube the way a browser does, with
[yt-dlp](https://github.com/yt-dlp/yt-dlp) as the engine, and keeps three things true:

- **Every request leaves through a tunnel of its own.** At install the library registers a Cloudflare WARP
  device (no account, no sign-in) and runs a WireGuard tunnel in a container that only this helper's
  traffic uses. YouTube sees the tunnel, not your home address. If YouTube refuses the tunnel, the
  refusal is yours to read, and your own address is untouched.
- **It paces itself.** One request every twelve seconds, which is the rate we measured YouTube to accept
  from a tunnel all day. A research run with twenty YouTube calls takes four minutes longer than it would
  without; that is the price.
- **It keeps data, never media.** Titles, descriptions, upload days, view and subscriber counts with the day
  they were read, captions, and the transcript of a video's audio when there are no captions. The audio
  it transcribes is deleted when the transcript is written. No video is downloaded.

Two things to know before you install it. Reading YouTube this way is against YouTube's terms of service;
the library is a reader, not a downloader, but the terms are the terms. And it breaks when YouTube changes
its pages, which happens a few times a year; `researchzosho video install` updates yt-dlp, and
`researchzosho update` updates the library.

## Setting it up

You need Docker (Docker Desktop on a Mac or Windows, the `docker` package on Linux) and Python 3.

```
researchzosho video install
researchzosho video status
researchzosho video test "iaido"
```

`install` downloads two small programs ([wgcf](https://github.com/ViRb3/wgcf), [Deno](https://deno.com))
into the library's own folder, registers the WARP device, starts the tunnel and the token provider as
containers that come back when the machine does, makes a Python environment with yt-dlp in it, and
starts a third container that transcribes audio (`faster-whisper`, on the CPU, a small model; a longer
video takes a few minutes). `status` says which of these are up and whether the tunnel answers.
`test` runs one search through the tunnel and prints what came back, so you can see that it works.

`researchzosho video stop` stops the containers; `start` brings them back. Nothing of this touches the
rest of the library.

| setting | default | what it does |
|---|---|---|
| `RESEARCHZOSHO_WHISPER_MODEL` | `Systran/faster-whisper-small` | the transcription model; `…-medium` is better and slower |
| `RESEARCHZOSHO_WHISPER` | the local container | a transcription server on another machine (any server with an OpenAI-style `/v1/audio/transcriptions`, such as speaches with a GPU); podcasts use the same server |

## Using it yourself

Five commands, for when you want to look something up rather than have a run do it.

```
researchzosho youtube search iaido kata okuiai            # videos, with channel, length, views and day
researchzosho youtube channels iaido                      # channels, with subscribers, small ones included
researchzosho youtube uploads @IaidoArchives              # a channel's latest uploads with their days
researchzosho youtube video https://www.youtube.com/watch?v=… --transcript
researchzosho youtube like @IaidoArchives                 # channels that make what this channel makes
```

A channel can be given as its address, its id (`UC…`) or its handle (`@name`). A video as its address
or its eleven-character id.

`video … --transcript` prints what is said as timed lines, `[5:12] …`, from the uploader's own captions
when there are some, else from YouTube's automatic captions, else from the library's own transcription
of the audio. Each line says where it came from.

**Channels like this one.** `youtube like` has the model write six search phrasings from the channel's description and
newest titles (never the channel's name), runs each as a channel search and a video search, and ranks the channels found by how
many phrasings found them, then by text similarity. Subscribers are shown beside each and not used for ranking, so a channel of
fifteen subscribers that makes exactly what you are looking for can come first. Measured on a list of 21 channels of one kind,
each as the seed: the top 20 held on average 2.8 of the other 20; every seed found at least one, and 11 of 21 found three or more.
A first version that built its phrasings mechanically from the channel's words found 0.2. One run takes about two and a half
minutes.

## In a research run

A question about channels, videos or what is shown in them gets the `youtube` field by itself, from its
words (channel, video, uploads, vlog, livestream, 動画, チャンネル, 유튜브, 视频 …), the way a question
about a program gets the `software` field. `--youtube` asks for it outright:

```
researchzosho research ask "which channels teach iaido in a way a beginner can follow, and which still post" --youtube
```

Without the helper installed, the field still applies: the run reads YouTube through web search, without
channel feeds, video details or transcripts, and `research ask` says so when it sends the question.

In the field a run keeps these rules:

- A channel is judged by what it makes and whether it still posts. Subscriber and view counts are
  reported with the day they were read, never used to rank.
- Channels are searched as well as videos, and in the subject's own languages, because the channels that
  matter for a Japanese art post in Japanese.
- A claim about what is shown or said in a video cites the moment: `https://www.youtube.com/watch?v=…&t=312s`.
  The citation check reads the transcript around that moment, not the whole video, and a sentence whose
  words are there settles without a model call.
- Who is on screen is known from a name shown or said, never from a face.

The write-up's relations join the library's graph: *uploaded by*, *shows*, *explains*, *appears in*,
*practitioner of*, *channel about*. A video a run read stays in the library as a source, under its
address, with its details and its transcript, so that the claim can be checked again later.

## What it does not do

It does not download videos, does not sign in, does not read comments, and does not read what YouTube
shows a signed-in viewer. It does not recognise anyone's face. It cannot read a video that YouTube
restricts to signed-in viewers or to a country the tunnel is not in.
