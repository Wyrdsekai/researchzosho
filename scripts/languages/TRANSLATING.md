# Translating the interface: the rules

ResearchZosho is a research library a person runs on their own machine. They ask it questions; it researches the web with a
local or hosted model, files reports with their sources, keeps subjects on shelves, follows channels, podcasts and books,
translates documents, and has a chat with "the Librarian". These rules are for whoever writes or corrects a language's file,
a person or a model.

Read the language's glossary (`glossary/<code>.md`) first. Its terms, its form of address and its typed phrases are decided:
use them, and add to it when a new term has to be chosen.

1. **Plain words, the meaning first.** The result reads as if a developer who writes plainly wrote it in the language, for a
   person who is not a programmer. Not a word-for-word rendering, no marketing voice. A button or a tab is one or two words; a
   sentence says what happens.
2. **These stay exactly as written:** placeholders `{0}` `{1}` (moved where the grammar wants them, never dropped or added);
   HTML tags with their attributes, and entities; anything in backticks; `--flags`; `/commands`; the words `researchzosho` and
   `zosho`; setting names and their values; file names, paths, addresses; the product's name; the names of models, hosts and
   other programs; the marker `[general knowledge]`. `check.py` fails a file when one of these changes.
3. **Blanks and line breaks at the two ends of a string stay as the English has them.**
4. **Counts.** The program picks the first text when the number is exactly 1 and the second for every other number, 0 included.
   The second must be right for every number it can get. Where a language has more than two forms, use a wording that does not
   change with the number (the noun before the number, as in "Files: {0}").
5. **Nothing agrees with a placeholder.** An article, a preposition's contraction, an adjective or a past-tense verb before a
   `{0}` cannot know the gender, number or first sound of what arrives. Word the sentence so nothing has to: a colon form, or
   the placeholder in quotation marks after its noun.
6. **A key ending in ` ⟦…⟧`** is a word with its meaning beside it. Translate the word for that meaning only; the marker never
   appears in the translation.
7. **What a person types.** A quoted example of what to type in the chat must be a phrase the chat understands: it has to be in
   `chrome/words/<code>.json` under its meaning, in the form the help shows. The phrases that name a model ("using deepseek",
   "checked by kimi") are read in English only and stay in English, and the text says so. A question that wants yes or no
   carries no hint in its string: the program adds the hint from the words file.
8. **The language's own typography:** its quotation marks, its punctuation and spacing, its way of writing numbers with units.
9. **One word per thing.** Each of the program's recurring nouns (library, subject, shelf, report, claim, source, run, field,
   dossier, tier, note…) has one word in the glossary, used every time.
10. **Helplines** (the sentences with phone numbers for a person in crisis): numbers, service names and hours exactly as given;
    plain, calm wording; nothing added, nothing left out.

## The words the chat reads (`chrome/words/<code>.json`)

- Each list holds what people really type for that meaning: the natural variants, with and without accents where people drop
  them, the polite and the short form. The longest phrase a message holds wins, whatever file it is from.
- A phrase must not also mean something else. "No notes" is never a plain no. A word that names a subject of research does not
  belong in the lists that send a question to the models turn (physics has a Standard Model).
- `yes` holds the language's plain yes, which may be followed by more words. `yes.alone` holds every other yes: the letter of
  the hint, and words that can also begin an ordinary sentence (si, claro, да). Those count only as the whole answer or before
  a comma.
- The first entry of each `lang.<code>` list is the form the program shows inside a sentence ("written in {0}").
- An entry after `re:` is a Java regular expression; keep it valid.
