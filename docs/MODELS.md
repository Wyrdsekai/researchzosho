# Which model

ResearchZosho ships no model. Research runs need a model server that speaks the OpenAI chat API. That
can be one on your own machine (llama.cpp, Ollama, LM Studio) or a hosted API with a key. Asking what
the library already holds works without a model.

The choice depends on **VRAM**: the memory on your graphics card, not the computer's RAM. `nvidia-smi`
shows it on an NVIDIA card; on an AMD graphics chip it is what the BIOS set aside for it, and
`researchzosho models` reads it from the machine. On a Mac with Apple silicon the card shares the machine's
unified memory, so read the tiers against about two thirds of that. This page has a measured recommendation
for both kinds: the local models by card, first, and then the hosted models by what they got right,
how long a run took and what it cost.
`researchzosho models` prints the row for the card it finds, with the command that serves it.
`researchzosho model install` sets up the top row of that tier on demand. The model comes up when a
run needs it and goes away after twenty idle minutes. The embeddings model (Qwen3-Embedding-0.6B,
0.6 GB) runs beside it at the same address, for search by meaning.

## How the list was made

Eleven models were run through the same research questions on the same library, on one machine with
one card. Each was judged on what came out: whether the facts were right, how many claims the write-up
made, how many of its citations the citation checker could read against the source, and how long a
question took. The 9B and the 27B were then compared on five more paired questions. Both got the facts
right. The 27B extracted 25 claims to the 9B's 10, and 26 checkable citations to the 9B's 1.
Everything below comes from those runs, not from a leaderboard. A model not on the list was not
measured. That is different from not recommended.

## By VRAM, best first in each row

**24 GB of VRAM or more**

| model | file | what we saw |
|---|---|---|
| Qwen3.8-27B at 4-bit | `Qwen3.8-27B-UD-Q4_K_M.gguf` | the reference: the deepest answers, the most claims, the most citations the checker can read; 17 GB file |

**Linux or Windows, any card with 4 GB free, and 32 GB of RAM**

| model | file | what we saw |
|---|---|---|
| Qwen3.6-35B-A3B at 4-bit | `Qwen3.6-35B-A3B-UD-Q4_K_M.gguf` | a 35B model that uses about 3B of its weights for each token, so its experts run from RAM and only the rest sits on the card; 22 GB file. On a 16 GB desktop card it read 1,700 tokens a second and wrote 33; with 4 GB of that card, 1,200 and 34; on an 8 GB laptop card in daily use, 700 and 28. A whole research run served from the 16 GB box took 27 minutes. It reads about twice as many sources as the 27B in the same time and cites them more loosely: on the first question both answered, the citation check marked 27% of its cited sentences against the 27B's 11% |

`researchzosho model install` picks it for a Linux or Windows machine that cannot hold the 27B whole and has the RAM. The speeds above are Linux with CUDA. Under Vulkan, which Windows uses, the same model on the same 16 GB card read 540 tokens a second and wrote 25: about half the reading speed. A Mac keeps the rows below: its memory is one pool, so there is no RAM to put experts in. It sets the flags that matter: `--n-cpu-moe` for how many layers stay in RAM, `-b 4096 -ub 4096`, and one thread on each fast core.

**16 GB of VRAM**

| model | file | what we saw |
|---|---|---|
| gpt-oss-20b | `gpt-oss-20b-F16.gguf` | right, 8 claims, about 5 minutes a question; 13 GB in use with two 16k slots. The choice when speed matters |
| Gemma 4 26B-A4B at 4-bit | `gemma-4-26B-A4B-it-UD-IQ4_XS.gguf` | right and the most careful writer, 10 claims, about nine times slower; 14.6 GB in use |
| Gemma 4 12B at 4-bit | `gemma-4-12b-it-Q4_K_M.gguf` | right, 10 claims, six times slower; 9 GB in use, the most room for context |

**8 GB of VRAM**

| model | file | what we saw |
|---|---|---|
| Qwen3.5 9B at 4-bit | `Qwen3.5-9B-Q4_K_M.gguf` | right and deep, 10 claims, half its citations readable, about 11 minutes a question; 5.9 GB in use with one 16k slot |
| Gemma 4 12B, smaller 4-bit file | `gemma-4-12b-it-IQ4_XS.gguf` | right, 10 claims, about 30 minutes a question; 7.3 GB in use with one 16k slot |

**4 GB of VRAM**

| model | file | what we saw |
|---|---|---|
| Gemma 4 E4B at 4-bit | `gemma-4-E4B-it-Q4_K_M.gguf` | right, 9 claims, the best citation reader of the small models, about 6 minutes a question; 3.6 GB in use |

**2 GB of VRAM**

| model | file | what we saw |
|---|---|---|
| Gemma 4 E2B at 4-bit | `gemma-4-E2B-it-Q4_K_M.gguf` | the claims came out right, but its write-ups were headings with no text; 2 GB in use. A hosted API is the better answer this small |

The files are the ones Hugging Face lists under `unsloth/<model>-GGUF`. `researchzosho model install`
checks every download against a recorded sha256 and refuses a mismatch.

## Measured and not recommended

- Qwen3 14B and Mistral Small 24B: a fact wrong each; Mistral needs 19 GB.
- The 27B at 3-bit: slow, and it under-finds.
- Qwen3 8B and Llama 3.1 8B: thin answers.
- Granite 4.1 8B: right, but 9.4 GB in use with one 16k slot, so not an 8 GB fit, and one unit slip.
- Phi-4-reasoning-plus, Falcon-H1 7B, SmolLM3 3B, LFM2 8B, Granite 4.0 micro: copied the prompt, answered
  without sources, or got the facts wrong.

## What the runs need from a server

- **Tool calling.** The readers search and fetch through tools. With llama.cpp that means `--jinja`, so
  the server applies the model's own chat template. Without it, tool calls come back as prose.
- **Context.** 16k per reader is the floor the list was measured at. 32k gives the write-up more
  evidence. The `--parallel` count is how many readers work at once. `researchzosho research workers <n>`
  should not exceed it.
- **Reasoning off or low** where the model has the switch (`--chat-template-kwargs`). Otherwise a reader
  spends its turn thinking instead of reading. The serve commands from `researchzosho models` carry the
  right setting per row.

## Serving it yourself

`researchzosho models` prints the exact llama.cpp command for your card. It looks like this:

```
docker run -d --name researchzosho-model --gpus all -p 127.0.0.1:8080:8080 -v ~/models:/m \
  ghcr.io/ggml-org/llama.cpp:server-cuda -m /m/<file> --host 0.0.0.0 --port 8080 --jinja \
  -c 16384 --parallel 1 -ngl 99 --flash-attn on
```

On an AMD or Intel graphics chip the image is `server-vulkan` and `--device /dev/dri` takes the place of
`--gpus all`; an AMD Instinct card takes `server-rocm` with `--device /dev/kfd --device /dev/dri`.
`researchzosho models` prints the right one for the machine it runs on, and `model install` picks it by itself.

Then set `RESEARCHZOSHO_DRIVE=http://127.0.0.1:8080` in your config, or run `researchzosho setup` to
have it found. On a Mac, llama.cpp's Metal build runs the same files. `model install` sets it up as a
launchd agent. On Windows, the Vulkan build runs on any card. `model install` sets it up as a logon
task.

## Hosted models, measured (October 2026)

Any OpenAI-compatible endpoint works, and the Claude API and OpenAI's own API have clients of their own.
Name one with `researchzosho models add <name> <host word> <model id> --prices in/out[/cache]`, keep its
key with `models key <host> --file <file>`, and pick it per run with `--model <name>`, or let `auto`
choose. Your documents go to that server and nowhere else.

**Every price and cost on this page is from the providers' lists as read on 16 October 2026.** They change their
prices often, so read the list before you count on a figure, and keep your own current with
`researchzosho models set <name> --prices in/out/cache`; the costs the library reports use the prices you gave it.

The picks, from the runs below:

- **Cheapest that is right: DeepSeek V4.1 Flash through OpenRouter.** 41 to 44 of the 46 facts in three
  runs, none wrong, about 11 minutes and 5 cents a run. `models add deepseek openrouter deepseek/deepseek-v4.1-flash --prices 0.032/0.144/0.002`
- **Fastest that is right: the same model through Nebius.** 43 of 46, 7 minutes, 90 cents.
  `models add deepseek-fast nebius deepseek-ai/DeepSeek-V4.1-Flash --prices 0.30/1.20/0.30`
- **The careful one: Kimi K3 through OpenRouter.** 45 of 46, 13 minutes, about $2.30. GPT-6.1 Sol direct
  and Claude Fable 5.1 are level with it at 44, Sol for $2.50 and Fable for $10.
  `models add kimi openrouter moonshotai/kimi-k3 --prices 0.30/11.94/0.21`
- **Free: the local Qwen3.8 27B.** 23 to 26 of 46 with one wrong, 40 to 47 minutes on one 24 GB card.
  It reads fewer pages in the time and its write-up names fewer facts; what it names is right.

The runs: one research question with 46 facts that a 2026 handbook settles, the same seed library, the
same tools, one run each unless noted, the write-up read against the key by two judges. Costs are at
the providers' list prices of 16 October 2026, OpenRouter's from its public model list, applied to the tokens the
servers reported.

| model, host | facts of 46, Fable then Sonnet | minutes | cost a run |
|---|---|---|---|
| Kimi K3, OpenRouter (three runs) | 45, 44 · 38 to 45 | 13 | $2.21 to $2.41 |
| GPT-6.1 Sol, OpenAI direct (three runs) | 44, 44 · 40 to 44 | 15 to 17 | $2.25 to $2.56 |
| Claude Fable 5.1, direct | 44, 44 | 10 | $10.37 |
| GLM 5.3 Flash, OpenRouter | 44, 44 | 31 | $0.33 |
| DeepSeek V4.1 Flash, OpenRouter (three runs) | 37, 42 · 41 to 44 | 11 to 12 | $0.05 to $0.06 |
| DeepSeek V4.1 Flash, Nebius | 43, 41 | 7 | $0.90 |
| Claude Sonnet 5.5, direct | 41, 40 | 9 | $1.81 |
| Gemini 4 Argon, Google | 41 | 41 | $1.83 |
| DeepSeek V4.1 Flash, Hugging Face router (cheapest provider) | 39, 38 (1 wrong) | 8 | $0.76 |
| MiniMax M3, OpenRouter | 39, 37 | 11 | $0.14 |
| GPT-6 Luna, OpenRouter | 38, 34 (1 wrong) | 15 | $0.10 |
| DeepSeek V4 Pro, OpenRouter | 37, 35 (1 wrong) | 13 | $0.43 |
| Claude Opus 5.5, direct | 36, 36 | 10 | $3.16 |
| Claude Haiku 5.5, direct | 30, 22 | 6 | $0.08 |
| Qwen3.8 27B, OpenRouter (the local model, unquantised) | 28, 26 (2 wrong) | 37 | $0.78 |
| Gemini 3.8 Flash, Google | 28, 21 (3 to 4 wrong) | 15 | $0.95 |
| GLM-5.3, OpenRouter | 27, 22 (2 to 3 wrong) | 16 | $0.66 |
| Nemotron 3 Ultra, OpenRouter | 26, 25 (2 wrong) | 19 | $0.97 |
| local Qwen3.8 27B, 4-bit, one card (three runs) | 23 to 26 (1 wrong) | 40 to 47 | free |
| GPT-6 Luna, OpenAI direct (three runs) | 23, 29, 19 | 8 to 12 | $0.09 to $0.12 |
| MiMo-V2.6-Pro, OpenRouter | 20, 11 (1 to 6 wrong) | 41 | $0.59 |
| Hy4 preview, OpenRouter | 19, 13 (2 wrong) | 18 | $0.90 |

What the table says. The frontier models are level with each other at the top; the difference between
them is price, and it is a factor of two hundred between DeepSeek Flash and Fable for the same facts. A host
changes speed and price more than it changes the answer: the same DeepSeek model gave the same facts
through OpenRouter, Nebius and Hugging Face. GPT-6 Luna is not reliable from run to run and MiMo, Hy4
and the big GLM-5.3 trail their smaller or cheaper relatives. Prices are the providers' of 16 October
2026 and move; `researchzosho models set <name> --prices …` keeps yours current, and `researchzosho spend`
says what your runs came to.

## Two models

`RESEARCHZOSHO_JUDGE_DRIVE` names a second server for the planning, the critic, the write-up and the
citation check. The readers keep the first. A stronger hosted model can judge while a local model
reads. The guide's section 12 has the details.

## The chat

`researchzosho chat` runs on the same model as the research runs. The same two-turn conversation was
tried on five models against one library on 2026-09-14. The first turn asked what the shelves hold.
The second asked which entry is the most recent and who wrote it.

| model | what happened |
|---|---|
| Qwen 3.8 27B | Found all six relevant entries, sorted them, cited each, answered the follow-up from one look-up. 27 s and 9 s a turn. |
| gpt-oss-20b | Found two of the three children's books, cited them, opened the entries for the follow-up and answered right. 4 s a turn. |
| Gemma 4 12B | Found the entries and cited them, but did not open them for the follow-up and said it did not know the dates the entries carry. |
| Qwen 3.5 9B | Found two of three, cited them, opened both for the follow-up and answered right. 4 s a turn. |
| Qwen 3.5 4B | Right on one run; on another it cited four entry ids that do not exist. The chat now admits such a reference under the reply. |

The 9B and gpt-oss-20b hold the conversation. The 27B sees more. The 12B answers but does not dig.
The 4B cannot be trusted to cite. The chat's tool list is about 3,900 tokens, so a model served with
an 8k window has little room left for the entries it opens. 16k or more is better for the chat.
