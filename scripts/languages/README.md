# The interface's languages

Everything the program itself says to a person is in one file per language, keyed by the English text:
`librarian/src/main/resources/org/researchzosho/librarian/chrome/<code>.json`. English needs no file. The Japanese file is the
reference: it decides the list of keys, and every other file must have exactly those keys.

What the chat reads in a person's own words (the short answers, the asks it answers itself) is in `chrome/words/<code>.json`,
one meaning to a list of phrases. Its first line, `_how`, explains the three kinds of entry.

The languages other than English and Japanese were translated by a model, each with a glossary of its own
(`scripts/languages/glossary/<code>.md`). No native speaker has read them. A correction from someone who reads the language is
welcome: change the value in the language's file, run the check, and note a changed term in its glossary.

## When the program's words change

A string added, reworded or removed in the code changes the Japanese file first. The suite then fails until every language file
has the same keys (`ChromeFilesTest`), so a change of wording is finished in every language before it is committed.

1. Change the code and `chrome/ja.json` (the English key and its Japanese).
2. `scripts/languages/delta.py <folder>` writes `<folder>/<code>.todo.json` for each language: the strings it lacks, numbered,
   with the English and the Japanese.
3. Each language's strings are translated by the rules in `TRANSLATING.md`, with that language's glossary, into
   `<folder>/<code>.new.json` as `{"<number>": "<translation>"}`.
4. `scripts/languages/apply.py <folder> <code> …` writes each language's file in the Japanese file's order and layout, drops the
   keys no longer used, and checks the result.
5. `scripts/languages/check.py` checks every file at any time: the same keys as the reference, and in every value the
   placeholders, tags, commands and setting names as written.

## A word with two meanings

One English word may need two words elsewhere ("Keep" the page and "Keep" the button). The code names the meaning:
`tc("Keep", "a button that saves the thing beside it")`. The key in each file is then `Keep ⟦a button that saves the thing beside
it⟧`; English shows the word alone, and the marker never appears in a translation.

## A new language

Add its code to `Chrome.FILES`, its own name to `Chrome.name`, a words file `chrome/words/<code>.json` with every meaning the
other files have, and an empty `chrome/<code>.json` (`{}`); then follow the steps above from step 2. The tests that read every
file present (`ChromeFilesTest`, `ChromeTest`, `ChatWordsTest`, `ChatLanguageTest`) check the new one with no change.
