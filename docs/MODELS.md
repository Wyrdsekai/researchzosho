# Which model

ResearchZosho ships no model. Research runs need a model server that speaks the OpenAI chat API: one on
your own machine (llama.cpp, Ollama, LM Studio) or a hosted API with a key. Asking what the library already
holds works without any model.

The choice depends on **VRAM**: the memory on your graphics card, not the computer's RAM. `nvidia-smi` shows it
on Linux and Windows; on a Mac with Apple silicon the card shares the machine's unified memory, so read the
tiers against about two thirds of that. This page is the measured list. `researchzosho models` prints the row for the card it finds, with the command that serves it;
`researchzosho model install` sets the top row of that tier up on demand, so the model comes up when a run
needs it and goes away after twenty idle minutes.

## How the list was made

Eleven models were run through the same research questions on the same library, on one machine with one
card, and judged on what came out: whether the facts were right, how many claims the write-up made, how many
of its citations the citation checker could read against the source, and how long a question took. The
9B and the 27B were then compared on five more paired questions. Both got the facts right; the 27B
extracted 25 claims to the 9B's 10, and 26 checkable citations to the 9B's 1. Everything below reads from
those runs, not from a leaderboard. A model not on the list was not measured, which is different from not
recommended.

## By VRAM, best first in each row

**24 GB of VRAM or more**

| model | file | what we saw |
|---|---|---|
| Qwen3.8-27B at 4-bit | `Qwen3.8-27B-UD-Q4_K_M.gguf` | the reference: the deepest answers, the most claims, the most citations the checker can read; 17 GB file |

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

The files are the ones Hugging Face lists under `unsloth/<model>-GGUF`. `researchzosho model install` checks
every download against a recorded sha256 and refuses a mismatch.

## Measured and not recommended

- Qwen3 14B and Mistral Small 24B: a fact wrong each; Mistral needs 19 GB.
- The 27B at 3-bit: slow, and it under-finds.
- Qwen3 8B and Llama 3.1 8B: thin answers.
- Granite 4.1 8B: right, but 9.4 GB in use with one 16k slot, so not an 8 GB fit, and one unit slip.
- Phi-4-reasoning-plus, Falcon-H1 7B, SmolLM3 3B, LFM2 8B, Granite 4.0 micro: copied the prompt, answered
  without sources, or got the facts wrong.

## What the runs need from a server

- **Tool calling.** The readers search and fetch through tools. With llama.cpp that means `--jinja`, so the
  server applies the model's own chat template; without it tool calls come back as prose.
- **Context.** 16k per reader is the floor the list was measured at; 32k gives the write-up more evidence.
  The `--parallel` count is how many readers work at once; `researchzosho research workers <n>` should not
  exceed it.
- **Reasoning off or low** where the model has the switch (`--chat-template-kwargs`), or a reader spends its
  turn thinking instead of reading. The serve commands from `researchzosho models` carry the right setting per
  row.

## Serving it yourself

`researchzosho models` prints the exact llama.cpp command for your card. The shape is:

```
docker run -d --name researchzosho-model --gpus all -p 127.0.0.1:8080:8080 -v ~/models:/m \
  ghcr.io/ggml-org/llama.cpp:server-cuda -m /m/<file> --host 0.0.0.0 --port 8080 --jinja \
  -c 16384 --parallel 1 -ngl 99 --flash-attn on
```

Then `RESEARCHZOSHO_DRIVE=http://127.0.0.1:8080` in your config, or `researchzosho setup` to have it found.
On a Mac, llama.cpp's Metal build runs the same files; `model install` sets it up as a launchd agent.
On Windows, the Vulkan build runs on any card; `model install` sets it up as a logon task.

## A hosted API instead

Any OpenAI-compatible endpoint works: `RESEARCHZOSHO_DRIVE` is the base address, `RESEARCHZOSHO_MODEL`
the model name the provider expects, `RESEARCHZOSHO_API_KEY` the key. Your documents go to that server and
nowhere else. `researchzosho setup` asks for these and checks them with one question.

## Two models

`RESEARCHZOSHO_JUDGE_DRIVE` names a second server for the planning, the critic, the write-up and the
citation check, while the readers keep the first. A stronger rented model can judge while a local model
reads. The guide's section 12 has the details.
