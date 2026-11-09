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
- **It paces itself.** One search every twelve seconds, which is the rate we measured YouTube to accept
  from a tunnel all day; reading a video — its details and its captions come in one request — or a channel's
  list, one every five seconds, measured on the same tunnel with a hundred videos in a row. A research run
  with twenty YouTube calls takes a few minutes longer than it would without; that is the price.
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
| `RESEARCHZOSHO_YOUTUBE_KEY` | unset | a YouTube Data API key, for the candidate search below: videos in date order, which YouTube's own search never gives. Free, 10,000 units a day; a search page costs 100 |
| `RESEARCHZOSHO_YOUTUBE_API_PAGES` | 20 | the most Data API pages one candidate search makes |

## Using it yourself

Five commands, for when you want to look something up rather than have a run do it.

```
researchzosho youtube search iaido kata okuiai            # videos, with channel, length, views and day
researchzosho youtube channels iaido                      # channels, with subscribers, small ones included
researchzosho youtube uploads @IaidoArchives              # a channel's latest uploads with their days
researchzosho youtube uploads @IaidoArchives --all        # every upload, newest first (a thousand in one request)
researchzosho youtube uploads @IaidoArchives --match okuiai   # every upload whose title carries the words (or /a pattern/)
researchzosho youtube video https://www.youtube.com/watch?v=… --transcript
researchzosho youtube like @IaidoArchives                 # channels that make what this channel makes
```

A channel can be given as its address, its id (`UC…`) or its handle (`@name`). A video as its address
or its eleven-character id.

`video … --transcript` prints what is said as timed lines, `[5:12] …`, from the uploader's own captions
when there are some, else from YouTube's automatic captions, else from the library's own transcription
of the audio. Each line says where it came from. The captions come in the same request as the video's
details and are kept under the helper's folder (`researchzosho video status` says how many), so a second
question about the same video asks YouTube for nothing. With `--match`, a run can do what a harvest does:
list a channel once, keep the uploads about a subject, then read each with `video … --transcript`.

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

## Finding what you saw

Something you saw in a video a while ago, found again. A video the library has read keeps three timed tracks, each
as lines with their second: what was **said** (the transcript), the **words shown** on screen (burned-in subtitles,
labels, product names, the steps of a how-to), and what is **seen** (a line describing each frame). The Video page
("Video" in the menu), `/video saw` in the chat and `researchzosho video saw` at the command line search all three
by words and by meaning and answer with the moment and a link that opens the video there:

```
researchzosho video saw pouring the tea into blue cups
researchzosho video saw warm up tortillas
```

What was said is kept when the video is read. The words shown and what is seen are kept the first time a picture is
looked for, from YouTube's own small frames of the video: the preview frames YouTube makes for the seek bar, one
about every five seconds, a few hundred kilobytes for the whole video. A model that reads pictures reads the words
off each frame and says what is seen in it; a frame that shows the same picture as the one before it is not read
twice. A video YouTube has no such frames for has frames cut from a small copy instead, one every fifteen seconds
and at each change of scene.

A shot of a second or two falls between YouTube's frames, so the library also cuts the changes of scene from a small copy of the video and reads the ones the small frames do not show, up to forty a video, kept beside the rest as the shots between the frames. It is on unless `RESEARCHZOSHO_VIDEO_SCENES` is off; `RESEARCHZOSHO_SCENE_CHANGE` says how much a frame must differ from the last to count as a change, 0.35 unless you set it. A shot shorter than that difference is still missed.

A video the library has not read can be looked at by its address: `researchzosho video look <address> <what to look
for>` (the Video page's "Look at a video" form, or `library_look` for a program) reads its frames and transcript, keeps
them, and answers with found, maybe or no and the second; `--sparse` reads a third of the frames.

For the picture in your head, describe what you remember seeing and say what is in it: a person, a thing, a place,
words on screen. The library reads the videos it has read that the model has not looked at yet, newest first, up to
twelve in one search; searches the words shown and what is seen for what you remember; then looks again, at full
size, at the nearest moments, and asks the model your own question of each: found, maybe or no, and why. It answers
with the video, the second and why, on the Video page and the runs page, and says which model looked and what it
cost: the frames read, the moments looked at again, the time. It needs a model that reads pictures
(`RESEARCHZOSHO_VISION_DRIVE` and `RESEARCHZOSHO_VISION_MODEL`, or the library's own model run with its picture
file); without one it says so and answers from what was said. A person is never told apart by their face: the model
says maybe for such a picture of someone, and found only when the name is on screen.

```
researchzosho video picture a red kettle on a wooden table --kinds thing
/video picture a man in a blue apron pouring tea kinds: person
```

What the model read is kept under the video's address with the model's name, so the next search reads nothing
again, and a second model's reading of the same video is kept beside the first; the Video page's table says which
models have read each video. The small frames are small: writing in them is read when it is the size of a
subtitle or a label, and a phone's screen or the small print of a slide is beyond them.

Look at one video. Give the library a video's address and what you remember of it, one thing after another, and it
looks at that video, on the shelves or not: it reads the page and the transcript, reads the frames into the same three
tracks, sparsely at first (the first minute whole, then every fourth frame) or whole, keeps them so the recall finds
them afterwards, and answers each thing you named with found, maybe or no, the second it was seen at and why. Whether
anyone talks is read from what was said; the rest the model judges by looking at the nearest moments. It says what the
look cost: the frames read, the moments looked at, the time. This is the look a research run uses when a question
names things one sees in a video rather than things its page says.

## Candidates for a video you remember

A video you watched once and cannot name is almost never a popular one, and YouTube's own search ranks by popularity: the
right video sat sixth for the plain name of the thing in its own language and dropped out of the results as soon as a
remembered detail was added to the words. So the library does not ask the model to search for it, and a research run about a
video you remember has no ranked search on its table at all. The model, or you, gives
the name of the thing the video makes or shows, two to four words, once in each language the video may be in, and what
you remember of the video itself in short phrases of your own words, and the library gathers the candidates itself. It first has the model name the thing, plainly, in the languages YouTube is large in
(Japanese, Korean, Chinese, Spanish, Portuguese, German, French, Russian), because you seldom know the language of a silent
video's title; the answer says which names it searched under. Then:

- Deep searches of its own plain phrasings ("apron tutorial", "how to make apron", "エプロンの作り方", "앞치마 만들기"), a hundred
  results each instead of twenty. No remembered detail ever goes into a search of YouTube; a title seldom names one.
- The pages where other people describe a video in the words you remember, when you give them (`--remembered`): what
  happens in it, what is seen or heard, who or what appears, a few words each. A title seldom says what happens in a
  video. A blog that posted it, a list that includes it or a forum where someone asked about it often does. The library
  makes its own web searches: the thing's name with each phrase, each phrase with the next, the names your phrases carry
  (a character, a brand, a year) together with the thing, each phrase with the word youtube, and the first ones again
  over youtube.com alone; in your language and, shorter, in each other language you named the thing in (the model says
  your phrases again in those languages; it writes no search). It takes the YouTube videos among the results, up to fifty
  results a search where the index gives that many, and a video whose address a result's own words carry, an answer on a
  question site or a line of a list, and reads up to a hundred of the pages, the ones whose title and
  snippet hold the most of what you remember first, three of one site at most, for the videos they link or embed. A
  video found this way says which site led to it ("found by web: example.org"). This source needs a web search index,
  Serper, SerpApi, Brave or your own SearXNG; without one it is skipped and the answer says so in one sentence.
- Every upload of the channels those searches surface, listed whole and kept where the title names the thing. A channel's
  list is complete and ranked by nothing.
- YouTube's Data API in date order over half-year windows, in the region and language of each name, when you set
  `RESEARCHZOSHO_YOUTUBE_KEY`. The key comes from the Google Cloud console: make a project, enable the YouTube Data API v3
  under APIs and services, and make an API key under Credentials. It is free, 10,000 units a day, and a search page costs
  100, so one search makes twenty pages at most (`RESEARCHZOSHO_YOUTUBE_API_PAGES`) and the day's count is kept
  (`researchzosho search status`). With the key, every candidate's upload day, length and own title come from the API
  too, a unit per fifty videos, so the dates and the length you give rule out the search and channel hits as well,
  whose listings carry no day. Without the key this source is skipped and the answer says so in one sentence.
- The other search indexes' view of youtube.com, through Serper, SerpApi, Brave or your own SearXNG when one is set up.
- Your own watch history, first of all, when you have added it (below).

The channels are listed in the order they came, the channel of every hit whose title names the thing first, in the order
the hits came, and the channels the channel search names after them, as many as the time allows, never the ones seen most
often: a hit's own channel is evidence that the channel makes the thing, the channel search's names are YouTube's ranking
of channels, the popular ones, and a channel that turns up on every search is a popular one.

Then the library drops what a machine can check, the videos you have already said no to, uploads outside the dates you gave,
lengths outside the minutes you gave, and titles in another language when you say which language the title or the
on-screen text is in (`--language ja`; a title the tunnel read in English whose own title is not known is kept, since the
tunnel reads a title in its own localisation), and lists the rest best fit first: a video from your history; then the
videos that hold what you remember, the most phrases first, where a phrase is held when the video's own title or
description, or the text beside its link on one page, has six in ten of its words, two at least, standing near one
another; then a title
that names the thing, and within those one video of each channel in turn, then each channel's second, and so on, so that
no channel's whole catalogue fills the top; within a channel, one that came from its list or the date-ordered API before
a ranked search's hit, then the upload nearer the time you gave. Views, subscribers and likes play no part, and neither does how many sources found a
video: what every engine finds is what is popular everywhere. The place a web search gave a result plays no part either:
a result that holds nothing you remember sits with the rest, and what several pages say of one video is never added up,
since a video many pages link is a popular one. A line says how many of the things remembered the video holds, and which. Each line gives the address, the title, the channel, the
upload day, the length, the script the title is written in, which sources found it, and the day you watched it when it is
from your history. Forty come back at a time; the whole pool is kept for the day under a key the answer ends with, so the
next forty cost nothing, and the same ask again that day reads the pool back without fetching.

A pool holds thousands and a look costs minutes a video, so the sift comes between them. It fetches each candidate's
thumbnail, YouTube's cover picture, and has the model that reads pictures say yes, maybe or no for what one picture can
show, the thing itself, its colours, a tag or label, a person shown or not; then, for those that pass, it
fetches the first storyboard sheet, the first minutes of the video as small frames, and asks it about what you remember of
the start. What no picture shows, that nobody speaks, a pocket sewn in the middle, is left to the look. The thumbnails go
nine to a call, laid out as one numbered grid the model answers with nine short lines, so a thumbnail takes well under a
second on a local model and a pool of three thousand is sifted in half an hour. A number
the model's answer leaves out is asked alone, and a model that answers three grids in a row with one word for every picture
cannot read a grid, so from there the thumbnails go one to a call, and the answer says which way they went and the pace
(`RESEARCHZOSHO_SIFT_GRID` sets how many to a call; 1 is one). The first sheets are fetched while the thumbnails are
still being judged, from the moment a thumbnail passes, so a sheet costs the model's reading alone, about nine seconds;
the sheet pass takes the thumbnails judged yes before those judged maybe, since the time seldom reaches every survivor. A call outside a
research run takes two hundred at a time, says how many are left, and the same call again goes on from there, since every
judgement and every picture is kept and nothing is judged twice. One sift runs in a library at a time, whatever its wording
or its pool: a second call while one runs comes back at once saying so, and the same call after the first ends reads the
first's judgements instead of making them again. The survivors come back best first, yes before maybe,
with the model's line on each, and the first five go to one look together.

```
researchzosho video candidates apron, エプロン, 앞치마 --before 2025-11
researchzosho video candidates apron, エプロン --remembered "nobody speaks; the measurements are written on screen at the start; a pocket on the front"
researchzosho video candidates tote bag, トートバッグ --after 2023-01 --minutes 5-40 --language ja --exclude dQw4w9WgXcQ
researchzosho video candidates apron, エプロン, 앞치마 --before 2025-11 --page 2
researchzosho video sift a striped apron; a red tag at the neck; the pattern pieces laid out at the start --from 2026-11-05-0a1b2c3d4e5f6071
researchzosho video sift a striped apron; a red tag at the neck; the pattern pieces laid out at the start --from 2026-11-05-0a1b2c3d4e5f6071 --page 2
```

The Video page has the same under "Candidates for a video you remember" and "Sift the candidates", the chat takes `/video
candidates apron, エプロン before 2025-11 remembered nobody speaks; a pocket on the front` and `/video sift a striped apron; a red tag from 2026-11-05-0a1b2c3d4e5f6071`, and a
program calls `library_candidates` and `library_sift`. The candidates take ten to twenty minutes through the tunnel. A
research run about a video you remember does all three, candidates, sift and looks, and has ninety minutes by default when
you set none.

## Your watch history

Google Takeout ([takeout.google.com](https://takeout.google.com), YouTube and YouTube Music, history) gives you your watch
history as `watch-history.json` or `watch-history.html`. Add it like any file:

```
researchzosho add ~/Takeout/YouTube\ and\ YouTube\ Music/history/watch-history.json
```

The library files one line a video, its id, title, channel and the day you watched it, and keeps the history on the shelves
as text. The candidate search reads it first, and a candidate from it says "you watched this on" the day. Adding a newer
export files only what is new. The history stays in the library like everything else the library holds: whoever may read
the library may read it, so add it to a library that is yours.

## What it does not do

It does not download videos, does not sign in, does not read comments, and does not read what YouTube
shows a signed-in viewer. It does not recognise anyone's face. It cannot read a video that YouTube
restricts to signed-in viewers or to a country the tunnel is not in.
