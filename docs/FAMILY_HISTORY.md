# Researching your family with ResearchZosho

This guide takes you from what your family already knows to a family tree with records behind it, and
then to what the people in it did in life: their work, their patents, the newspapers that named them,
the books that mention them.

You do not need to have done family research before, and you do not need a family-tree file. You need
ResearchZosho set up (`researchzosho setup` does it, and asks a few questions). Everything here runs on
your own computer. Your family's papers are never uploaded anywhere.

**Where you type the commands.** The lines in grey boxes are typed into a terminal (Terminal on a Mac,
PowerShell on Windows, any terminal on Linux), one line at a time, with Enter after each. If you would
rather talk than type commands, `researchzosho chat` opens a conversation with the Librarian, and the
same things work there as `/family …`.

**A few words this guide uses.**

| word | what it means here |
|---|---|
| claim | one fact, written as a sentence: "髙橋正一 was born in 1908." |
| source | where a claim came from: your aunt's notes, a web page, a newspaper page, a register |
| draft | a claim nobody has checked yet. Everything you read in starts as a draft |
| accepted | a claim you have looked at and agreed with. Only you can accept a claim |
| inbox | the list of drafts waiting for your decision |
| research run | the program searching and reading for you on one question. It takes 15 to 60 minutes, and you can do other things meanwhile |
| the model | the AI model on your computer that does the reading |

## The idea

A family historian works from records, and keeps four rules:

- A name is not a person. Two records belong to the same person only when something else agrees too:
  a spouse, a parent, an address, an occupation.
- Cite the record, not the web page that mentions it.
- When records disagree, keep both and say so.
- A search that found nothing is a finding. Write down where you looked.

ResearchZosho follows these rules in genealogy mode, which you ask for. It also keeps every claim
as a draft until you accept it. What your family says is where the work starts. It is not yet proof.

## 1. Gather what you have

Make one folder, for example `~/family-sources/`, and put in it:

- anything a relative wrote down: letters, notes, a memoir
- books about the family or the place, as EPUB or PDF
- photographs or scans of documents (a family register, a certificate, a headstone)
- `pages.txt`: web addresses that tell part of the story, one on each line. Wikipedia articles about
  relatives, a temple's page, a local history society's page. After an address you may say what the
  page is, behind a `#`. The note is told to the reader and kept on every claim from that page:

  ```
  # relatives with an article
  https://ja.wikipedia.org/wiki/…      # my grandfather
  https://example.org/shipyard-history # the firm he worked for; he is named in the 1935 chapter
  ```
- `notes.txt`: what you know yourself

Write `notes.txt` as plain sentences, one fact in each. Any mix of languages is fine:

```
My grandfather 髙橋正一 (たかはし しょういち) was born in 明治41年 in 広島県佐伯郡.
His younger brother 勇 was adopted into the 渡邊 family in 大正10年 and took the name 渡邊勇.
My aunt says 正一 worked for a shipyard in 呉 around 1935.
```

Four things make the notes work well:

- **One fact in each sentence.** Every claim keeps the sentence it came from, so short sentences are
  easy to check later.
- **Name people, do not only call them "my grandfather".** Say who it is once, "My grandfather is
  髙橋正一", and use the name after that. Say how you are related too: "My mother is 髙橋和子, his
  daughter. I am her son." This is how the people in your links file get tied to each other.
- **Write doubt the way you would say it**: "around 1935", "my aunt says", "I am not sure". It is kept.
- **Write names the way the family writes them**, and give a reading once, in brackets, when you know
  it. Write dates the way you know them: `明治41年`, `about 1885`, `before 1900`. The year is worked out
  for you.

A date can be a range too: `between 1880 and 1885`, `1880-1885`, `1850 or 1851`, or a tree file's
`BET 1850 AND 1860`, `EST 1850` and `CAL 1850`. The library keeps every date as a range of years and
compares the ranges. "About 1885" covers 1880 to 1890. It is never taken as exactly 1885.

Keep web addresses in `pages.txt` and sentences in `notes.txt`. A file that mixes the two is read as
notes, and its addresses are not opened. A note beside an address ("# my grandfather") helps the
program find the right person on a long page and is kept with the claims, but it does not by itself
make that person your grandfather in the tree. Say that in `notes.txt`.

## 2. Read it in

### 2.1 A whole folder

```
researchzosho genealogy read ~/family-sources
researchzosho genealogy read ~/family-sources --list                # what it would read, and in which order
researchzosho genealogy read ~/family-sources --again --skip epub   # everything again, except the books
```

It shows the order it will read in, then reads: your notes first, because they say who is who, then a
tree file, then your lists of links, then pictures, and the books last. By the time it reaches a book it
knows the family's names and reads only the parts that name them. A file it has read is skipped the next
time, so you can drop new material into the folder and give the same command again. `--list` shows the
order without reading anything. The books can take hours each, so start it in the evening.

Reading needs the model. When the model cannot be reached, or does not answer in time, the read says so
with the model's address, adds nothing from that file, and does not mark the file as read, even when the
model answered for part of it. When the model cannot be reached at all, the read stops after that file.
It stops the same way when the model's server answers with an error instead of an answer: a model that
is still loading, a key it does not take, or too many requests. For a model that is still loading it waits
about a minute first. The read says what to do: start the model or check its address
(`RESEARCHZOSHO_DRIVE`), wait a few minutes, or check the key. Then give the same command again: it reads
the files that are not marked as read.

### 2.2 One file at a time

```
researchzosho genealogy read ~/family-sources/great-aunt.docx --by "my great-aunt Hanae"
researchzosho genealogy read ~/family-sources/notes.txt --by "Mara"
researchzosho genealogy read ~/family-sources/pages.txt
researchzosho genealogy tell "髙橋源三郎 died in 昭和20年 in 広島." --by "Mara"
```

Read one file at a time when you want to say who wrote a particular account. Start with the short
things that are about your family. `--by` says who wrote the text, so you can always see where a claim
came from. `tell` takes a fact you type. In the chat the same commands are `/family read`,
`/family tell`, `/family tree` and `/family check`.

When you read a whole folder, a line in your notes says who wrote a book: "Kimie Hale wrote
community.pdf", "Kimie Hale - my father's cousin - wrote community.pdf" or "community.pdf is Kimie
Hale's memoir" makes the library read that file as her account, so that "my grandfather" in it is her
grandfather. The writer is the name before the first dash. What you wrote between the dashes is your own
account of her, and the reading of your notes files it. "Rose Hart translated community.pdf" names the
translator, who is not the writer: what the book marks as the translator's own note is hers.

When nothing says who wrote a book, the library asks the model once, from the book's first pages. It
keeps the answer only when the line the model quotes is in the book and names that person, and the read
says whom it found. With nobody found, "I" in the book is "the writer of community.pdf".

A web page is the source of what it says, never a person in it. What the model gives the page itself is
filed under the person the page's title names, and the note you wrote beside the page's address is filed
as your own account.

The model sometimes reads a parent and a child the wrong way round. When the text's own words say which
is which ("Tom's daughter Ann", "Ann, the daughter of Tom", 父 森田勇, 長男 正一, "my parents, Ruth and
Tom"), the words win, and the read says which facts it filed the other way round. Relation words count
as whole words with their modifiers: a grandfather ("my mother's grandfather", 祖父) is no parent, and
with no grandparent relation in the library the fact is filed as "relative of", with its words kept, so
the link pass can walk them; a stepfather, a father-in-law, a godfather, an adoptive or a foster parent
file their own relations, never a parent by birth; and "her father's younger brother" is a brother of the
father, so it is never filed as a brother of somebody else the model named.

Words that speak of somebody else give nobody a parent or a name. A parent word owned by or naming a third
person ("Nora Lindqvist, Tom's daughter"; 子爵 X 二男 beside a 婿養子), a likeness ("like a father to me"), a step
to no relative ("my mother's friend") and a party the words neither write nor stand for by a pronoun or the
account's own "I" make no parent claim; two names under 妻： are wives, not brother and sister; a description
such as "the writer's father" where the words say "her father" of somebody else is that person's father; and
a name in a sentence about somebody else is not the person's, nor is another spelling that is another person
of the text or that shares no given name with the person's. In the family's view, a parent claim and a sibling
claim between the same two people from one source cannot both stand: both are set aside as "the source is
read two ways", and nothing is asked about them. A parent claim between two people who are also written as
husband and wife is set aside too: one of the two is wrong, and the summary lists the two under Not settled.
When you change the note beside a link and read the list
again, the facts the old note gave are set aside as replaced by what the note says now.

A book's index writes a relation word beside a name: "Hale, Ann (mother), 12, 45". When the book's writer is
known, the library files that person in that relation to the writer, with the index line as the words: a
mother or father as the writer's parent, a wife or husband as married to the writer, a son, a daughter, a
brother or a sister as such, a grandparent or an uncle as a relative. Another given name in the note, as in
"(Ivy; wife)", is kept as another name of the person.

A book also quotes other people in their own words: an interview, a letter, a memoir. So in a book, a
sentence that says "I" or "my" counts as the writer's only when the model is sure of it after reading
the passage around it. Otherwise the library files the fact about a stand-in, "the speaker in
community.pdf, part 12", which is never searched for. If the words were the writer's own after all,
join the two:

```
researchzosho graph merge "the speaker in community.pdf, part 12" "Kimie Hale"
```

A short text takes a minute or so. When it is done you see something like:

```
The library found 6 people in this text. It wrote down 16 facts that this text states about them. A fact
is one simple statement, for example who a person's parents are, where and when a person was born, or a
job a person held.

For 2 of these 6 people, nothing in your library says that they have died or places them more than 110
years ago, so the library treats them as possibly still alive: a search asks about their work and public
life, not about their death.

The library left out 1 thing on purpose. It keeps a fact only when it can point to the exact words in the
text that say it:
  - "髙橋清 died in 東京" was left out, because the library could not find the words in the text that say it.
```

Each fact is kept together with the sentence it came from. At this point a fact is only what your text
says: nobody has checked it. The guide calls such a fact a **draft claim**. After this summary the
program tells you what comes next: letting the library search for more about these people (section 5 of
this guide). It shows the command, and asks whether to start the search now. It also lists what you can
look at in the meantime: the drawing, the mistakes, the list of facts and a person's life story.

### 2.3 What the reader keeps

When a text says something your library already has, nothing is written twice. The text is added to
that fact as one more source, and the summary says how many facts gained one. A fact counts as resting
on a record as soon as one of its sources is a record. A fact you disputed or retired is not filed again
when a text brings it back. A note on it says which text said it again. A fact the library disputed by
itself, because the review found another claim that says otherwise, a check found that its source does
not say it, or a paper it cites was retracted, keeps the text as one more source and stays disputed, so that later sources can settle it.

The reader keeps what a record says, including what it does not know:

- A register that says "father unknown" (父 不詳), or leaves the father's column empty, is kept as a
  fact. The research question for that person quotes the record and says where to look for the father.
- The informant who reported a death, the witnesses at a marriage and the godparents at a baptism are
  kept as people the record names, not as family. They become leads in the research questions. A death
  in the record belongs to the person the record is about, never to the informant, so a living informant
  is not counted as dead.
- A name that a text reads two ways, such as 森田勇 read もりた いさむ and もりた ゆう, keeps both
  readings, and the summary says so. A register that gives the reading beside the name settles it.
- A person's other names are kept as names, each with how it came: at birth, at marriage, on adoption,
  on entering a family as 婿養子, as heir, by a legal change, a pen or religious name. The kind is kept
  only when the words of the text say it (婿養子, 旧姓, née, adopted, pen name). When they do not, the
  name is kept with its kind not known yet, together with the text's own words, and the library asks
  you. A year is kept as the year a name came only when the text says the name came then, and only the
  year written with those words: "went to school in 1920 as Morita Kenji" does not date the name Morita
  Kenji, and "Born in 1905 as Endō Kenji, he went to school in 1920" dates Endō Kenji 1905. A name's
  family part counts only where it stands outside other people's names: "Kenji married Morita Haru"
  gives Kenji no name Morita. The spellings of one name (森田健二, もりた けんじ, Morita Kenji) are one
  name, not several.
- A person's sex is kept when the text's words say it: she, her, a wife, a widow, a daughter, 妻, 娘 for a
  woman, and he, his, a husband, a son, 夫, 長男 for a man. The word has to stand in a sentence about that
  person's own facts, and it has to be about that person. In "She married Tom Ellis", the "she" is not
  Tom. In "his wife Haru", the wife is Haru. A name says nothing at all, even when it has 夫 in it. When
  a person's sentences say both, no sex is kept.
- A relative the text does not name is written with the text's own words for them: "my grandmother" in
  notes.txt is "the writer of notes.txt's grandmother". The facts about such a relative are kept when one
  of their sentences has those words. So "My grandmother was born in Cork. She died in Leeds." keeps both
  facts. The words can be "grandmother", "Granny", 祖母 or おばあちゃん, "parents" for a father or a
  mother, or "mother" and "father" for a mother's father. A great-grandmother is not a grandmother. When
  the sentences are in a language the library has no words for, your model is asked. Otherwise the facts
  are left out, and the read lists them.
- Each person is filed under a name the text writes. When the model writes a name in a form the text
  never uses, such as katakana for a name the text gives only in Latin letters, the library files the
  person as the text writes them, by the whole name when the text writes it, and the summary says so.
- When the text writes a name in characters and in Latin letters side by side, as in Morita Kenji
  (森田健二, もりた けんじ), the person is filed under the characters, and the Latin letters and the kana
  are other spellings of the name. A family written that way, the Takahashi family (髙橋家), is filed in
  characters too.
- A name in characters and a name in Latin letters are one person when the text writes them together,
  one in brackets after the other, or when the model that reads the text gives the one as the other's
  other spelling or name. A reading alone does not join them: a text that writes 森田健二（もりた けんじ）
  in one place and Morita Kenji in another gives two entries, and the library asks you whether they are
  one person (3.10). Two entries your library already has are never joined by a read. Two names a fact of the
  text relates ("Kenji's grandson Morita Kenji"), or gives birth years far apart, stay two people.
- A family the text speaks of as a family (森田家, the Morita family, the house of Hale, the Endos) is
  kept as a family of its own, with its seat when the text names one. Who entered it or left it, and
  how, is kept with each person. A year the text gives for leaving ("left the Morita family in 1940") is
  the year they left, not the year they came in. Words that only name somebody, with no word for a
  family, make no family, and nor do words like "entered the family" that name none. 家 after a name
  and "the Morita family" name a family by themselves, and so does a name that begins with a small word, such
  as "the van Hale family". A family the text writes in Latin letters is the family written in characters
  when the text or your library ties the two: "the Morita family" is 森田 when the text writes Morita Isamu
  (森田勇), gives the reading もりた, or names the family both ways, or when your library already has the 森田
  family also written "the Morita family". "The house of Hale", "the Hales" and "the Hale
  line" name one when Hale is the family name of somebody in the text or of a family it names; otherwise
  the model is asked once whether the words speak of a family, so "the House of Commons" and "the
  Quakers" do not become families.
- A family is never a person. When the text puts a family where a person should stand, as in "the head
  of the Morita family" or "adopted as heir into the Takahashi family (髙橋家)", the library keeps it as
  that person's membership of the family: its head, or an entry by adoption, as its heir when the words
  beside the family say heir. The same holds when the model gives the family by its name alone (森田) as the
  household somebody heads, the one who adopted somebody, or the one an heir follows, and the text or your
  library has a family of that name. What the text says of the family itself, such as where it lived, is kept with the family.
- 婿養子 is three things at once, and the reader keeps all three from the same words: the adoption by
  the wife's parent, the marriage, and the entry into her family. The words are about the 婿養子 alone:
  his wife, who was born into the family, and anybody else in the same sentence keep what the words say
  of them. His wife is the parent's child the words call a daughter or a wife. When the words do not
  name her, the library asks you. An adoption the model gives without saying 婿養子 is taken as the
  婿養子's when the word 婿養子 stands with that adoption's own people, in any spelling the model gives them
  ("健二は…森田家の婿養子となり", "…as mukoyōshi (婿養子) of Isamu" for 森田勇, whom the model also writes
  Morita Isamu), or when the sentence writes the adopted man only as "he" and tells of no other adoption
  ("In 1932 he married Haru … and entered the family as mukoyōshi (婿養子)"). In "Kenji entered the Morita family as 婿養子, and his brother Masaru was adopted by Takahashi Shōichi",
  the brother's adoption stays an adoption whose kind the words do not state.
- A given name on its own, such as "Kenji" in a text that also has exactly one Morita Kenji, is that
  person only when the text writes the two together in brackets, or the model gives Kenji as his other
  spelling. Otherwise it is an entry of its own, and the library asks you whether the two are one person.
  When the text has two people with that given name, the library does not choose, and when a fact of
  the text relates the two ("Kenji's grandson Morita Kenji"), they are two people.
- A person the text names only by a family name, such as "Endo's son, Morita Kenji", is never made a
  person called Endo. The library writes that person down as the text describes them:
  `Morita Kenji's parent (written only as Endo)`, as a member of the Endo family. This holds when the
  model that reads the text names the father in full from another sentence, or gives Endo as one of the
  father's spellings, when the same words also name the Endo family ("Endo's son … left the Endo
  family"), and when your library keeps "Endo" as another name of the Endo family: the words decide, and
  the person the model named is the first of the people the library offers. "The second son of the Endo
  family" and 遠藤家の次男 make him a member of that family by birth, and his parent somebody of that
  family, written down the same way. A fact of that person's own life goes to the same described person when the
  same words write both. When another sentence tells it, such as "Endo died in 1921" or "Endo, the last
  head of the Endo family, died in 1921", it goes to `Endo family's member (written only as Endo)`, a
  member of the Endo family, because another sentence's Endo may be somebody else, and the library asks
  you who it is. A family is never given a person's death. "Endo's wife" makes Endo her husband;
  "Endo's younger brother" does not say whether Endo is a brother or a sister. When your library
  has exactly one person who carried that family name at that time, fits the words (a parent is at
  least twelve years older, for example) and has a second fact that agrees, such as a tree file that
  gives Morita Kenji's father, the library links the two and the summary of the read says so. Each
  such link comes with the command that takes it back, `researchzosho graph unmerge`, and the library
  does not make a link again after you take it back. Otherwise it asks you who it is, and lists the
  people it could be (`researchzosho genealogy who`, 3.10). A later read, a tree file or Geni can settle
  it: the library tries again after each of them. `genealogy reset` is a fresh start, not your answer:
  after it, the next read may make a link again. When the person it links to writes the family name in
  characters, and a source reads those characters as the word the text used (a tree file that writes
  遠藤健二 as Kenji Endo), the family made for "Endo" becomes the 遠藤 family, unless the two have
  different seats: then they stay two families. The summary says so, with the command that takes it back.

### 2.4 A tree file (GEDCOM)

```
researchzosho genealogy import aunt-tree.ged --dry    # says what the import would do, and changes nothing
researchzosho genealogy import aunt-tree.ged
```

Geni, Ancestry, MyHeritage and the desktop family-tree programs can all export a tree as a GEDCOM file
(on Geni it is in the Family menu). None of those sites lets a program search its records, so the
exported file is the way in. If a relative keeps the family's tree online, ask them for that file.

`--dry` reads the file and changes nothing. It says how many people are in it, who of them may still be
living by the file's dates, who would be kept apart, and who in the file may already be in your library,
with their birth years and the relatives they share.

Each person in the file is a person of their own, even when two of them share a name. A father and a
son both called Tom Ellis become "Tom Ellis (born 1851)" and "Tom Ellis (born 1890)". When no year tells
them apart, the library uses their number in the file, for example "Mari Morita (I3 in aunt-tree.ged)".
A person whose birth date cannot be within two years of the birth of somebody of the same name already
in your library is kept apart in the same way. A date given as a range, such as between 1840 and 1860,
counts as every year in it. The next import of the file finds the same people again.

Each person and relation becomes a draft claim with the file as its source. The import reads files from
older programs too: the ANSEL, Windows, DOS and Macintosh character sets, and UTF-16. It keeps:

- every name of a person. Each `NAME` is a name of its own, with its kind from its `TYPE`: birth (or
  maiden), married, aka, immigrant, professional, or the file's own word, such as 婿養子. A first `NAME`
  with no `TYPE` is the name at birth when another name of the person is one the file says came later,
  such as a married name. A married name written under a name (`_MARNM`) is a name taken at marriage, and
  an alias (`_AKA`) a name the person was also known as. A married name written as a family name alone
  (`/Ellis/`) keeps the person's given name: Ruth Ellis. A `NAME` whose `TYPE` says it is a spelling
  (Other Spelling) is another way of writing the name it spells, and a nickname is a name the person was
  also known as. The romanised form (`ROMN`), the reading (`FONE`) and a transliteration (`TRAN` with its
  `LANG`) under a `NAME` are other ways of writing that name, not names of their own. A name in Chinese
  characters, kana or hangul is written family name first, as the family writes it. The first `NAME` is
  the one the person is filed under, unless it is nobody in your library yet and another of the person's
  names, or one of its written forms, is exactly one person already there, and a second fact agrees: the
  file and your library give the same birth year, or the file gives the person a parent, a husband or
  wife, or a child that your library already records as that person's parent, husband or wife, or child,
  and that relative is the same person in both by more than a name: the birth years are the same too, or
  your library already holds that record of the same file under them. Then the file's person is that
  person: a file that gives 遠藤健二 first and 森田健二 second, with the birth year a book gave you,
  finds the 森田健二 that book gave, and the import says so and says which fact agrees. A shared name
  alone joins nobody, and neither does a relative who only has the same name, because a son's wife can
  carry his mother's married name and have a husband of his father's name, and a grandson can carry his
  grandfather's name: the file's person is kept as an entry of their own, the import says so, and
  `researchzosho genealogy who` asks you whether the two are one person. Birth years more than two years
  apart keep them apart, as they do for the first name.
  `researchzosho genealogy names "<person>"` shows every name with its kind and its years.
- an adoption the file records as an event (`ADOP`): its date, its kind (`2 TYPE 婿養子`, or an heir's
  adoption), and which parent adopted (`ADOP HUSB`, `WIFE` or `BOTH` under its `FAMC`). Only that parent
  becomes an adoptive parent. The other parent keeps what the file says of them, and is a parent by birth
  when the file says nothing. 婿養子 also enters the person into the family of the parent who adopted them,
  when the file describes that family. An `ADOP` that does not name its family (`FAMC`) belongs to the one
  family the file says adopted the person. When the file says several families adopted them, the import
  says that it could not tell which one the adoption's date and kind belong to.
- families as houses, lines or clans, with who belonged to them, how they came in and left, and when, if
  the file has the records ResearchZosho writes for them (`_HOUSE` and `_MEMBER`, section 8). A family is
  never made from a surname: two people named 遠藤 are not one family because of it.
- births, deaths, marriages, christenings and baptisms, burials, censuses, homes, moves, cremations and
  wills, and a date that has no place: a burial dated 1901 with no place is filed as "buried (1901)"
- how each child belongs to a family: born to it, adopted, fostered, or a step-child (`PEDI`, and the
  `_FREL` and `_MREL` some programs write). A step-parent and a foster parent are relations of their
  own. They are drawn dashed on the tree and do not count as parents by birth. A word the library does
  not know, such as sealing, is kept as a parent at low confidence, and the claim gives the file's word.
- each person's sex (`SEX`), and an age the file gives at an event (`2 AGE 42y`), kept as an age dated
  at that event
- godparents, witnesses and informants (`ASSO`), as people the record names, not as family
- every source the file gives for a fact, with the page it cites, the file's own rating of the source,
  and the date the source wrote its entry down. A birth taken from a death record then shows how long
  after the birth it was written.
- a person's FamilySearch id or another site's id (`_FSFTID`, `EXID`). `genealogy check` then says when
  two people have the same id (very likely one person) or two different ids from one site (two people).
- the file's research notes (`_TODO`), as open questions, whoever they are about
- a death, a burial or a cremation the file gives with neither a date nor a place, as the fact that the
  person has died

A restriction the file puts on a person (`RESN`) is read and left out: who may see your library is
decided by who may read it (section 3.7).

Anything the import does not read yet, such as schooling or pictures, is listed at the end, so you know
what stayed behind.

When you correct your tree in your family-tree program and import the file again, a changed birth,
baptism, marriage, death or burial replaces the draft from the earlier copy, and so does a name whose kind
or years changed, and a changed membership of a family. The earlier draft is kept, marked as replaced. A
fact you accepted or disputed is never replaced: the new one is put beside it for you to look at. An older
copy of the file corrects nothing.

If an earlier version of ResearchZosho took two people of one name in the file for one person, the
import tells you. To start clean from that file:

```
researchzosho genealogy reset --from aunt-tree.ged    # takes out the facts read from this file, and saves a copy first
researchzosho genealogy import aunt-tree.ged          # reads it again
```

A fact that another file or a research run also gives is not taken away: it stays with that other source,
and only the file you name is taken off it. The same is done to a fact the library disputed by itself, and
it stays disputed. Reading the file again adds it back as a further source.
The words after `--from` are matched against the files' own names, not the folders they are in. When they
are in the names of several files, or two files of that name are in different folders, the command lists
each file with its folders and takes back each of them, so give the whole path, as the list writes it, to
take back one. When the words name a list of web addresses, the reset takes out what the list's pages and
Geni profiles gave, and the next read of your folder reads the list again. The questions on the waiting
list that were written from the file's facts are taken away with them, and the next `genealogy research`
writes them again from what stays.

### 2.5 A Geni profile

```
researchzosho genealogy read "https://www.geni.com/people/Genzaburo-Takahashi/6000000012345678901" --steps 30
researchzosho graph merge "髙橋源三郎" "Genzaburo Takahashi"
```

This works once you have signed in to Geni (section 7). It takes the person, their dates and places,
their partners, parents and children from Geni. `--steps 30` goes on through the relatives' own
families, thirty profiles in all, one every ten seconds because that is what Geni allows. A Geni link
can also stand in your list of links.

When a profile has a birth surname (Geni's Birth Surname) that is not its last name, the person gets two
names, for a man as for a woman: the name they were born with, and the name Geni has them under now. A
middle name is part of both. Geni does not say how the name changed. Geni does say who is a man and who is
a woman, and the library keeps that as each person's sex. A later name that the husband or wife carried
when they married is worked out to have come with the marriage (3.8). Otherwise the kind of the second name
is not known yet, and `researchzosho genealogy who` asks you about it. Both names are one person's, because one
profile is one record. Geni's names in other languages, such as Japanese and English, are other ways of
writing those names, so a search finds the person under each of them. A profile an older version filed
with its name in another order ("ハル 森田") keeps that entry when it is read again, and the name as it is
written now becomes another name of it. A profile first met as a relative, under a shorter name, and read
later with its full name is one person under both, and the read says so with the command that takes it back.

A profile whose names Geni gives only in Latin letters arrives as a second person when you already have
that person in kanji. The second command makes them one person.

### 2.6 A whole book

```
researchzosho genealogy read ~/family-sources/town-history.epub --family 髙橋,Takahashi
```

A whole book names hundreds of people. `--family` keeps it to your family's names. Only the parts of
the book that name the family are read, and only facts about those names are kept. Old and new
character forms count as one name: 髙橋 and 高橋, 渡邊 and 渡辺. The command says which part it is on. A
book can take hours on a small machine, so run it overnight.

A book that is background, and not about your family, does not need this. Add it to the library with
`researchzosho add book.epub`. Research runs search your shelves before the web, and will cite it.

### 2.7 Photographs and scans

```
researchzosho model install                              # sets up a model that reads pictures, for the models that can
researchzosho genealogy read ~/family-sources/register   # reads the pictures in a folder, in the order of their names
```

Photographs and scans work when your model reads pictures. The model copies the writing as it is, old
character forms included, and writes □ for a character it cannot read. Printed text reads well. Old
handwriting is much harder: check what it read against the picture. Section 6.2 shows how.

Name your scans so that the pages of one record sort together: `register p1.jpg`, `register p2.jpg`,
or `Takahashi_1908 (1).jpg`, `Takahashi_1908 (2).jpg`. Pictures whose names differ only by the page
number are read as one text, so an entry that goes on to the next page stays one entry, and each fact
cites the pages it is on.

The name helps too. A year, a family name, or a word such as maybe or 不明 in it is told to the reader
as the file's label and kept with each fact. The facts themselves still come from the writing on the
page. Names a camera or a phone gives, such as `IMG_0012.jpg`, say nothing and are left alone.

### 2.8 Starting again

```
researchzosho genealogy reset                    # takes out what was read from your folder: says what goes and what stays, and asks
researchzosho genealogy read ~/family-sources    # reads the folder in again
researchzosho genealogy read --again             # reads the folder you read last once more, every file in it
researchzosho genealogy reset --all              # takes out everything, Geni and web pages too, and prints the commands that read them again
```

The library keeps a list of everything it has read for your family: each file of a folder, a file read on
its own, a list of links, a tree file, a picture, a web page, and a Geni profile with the `--steps` it was
read with, also one that stands in a list of links. An address is the same address however it is written,
with `%`-escapes or in characters, and a Geni profile is known by its number.

`genealogy reset` takes out the facts the library read from the files in your folder that you have not
checked yet, with the people, places, events, families and names that only those facts mention, and the
links between names the library made from them. It forgets that it read those files, so the next read of
the folder reads them all again. A person who stays, for example through a Geni profile, keeps the other
names those files gave, so the next read finds the same person. What came from Geni profiles and web pages
stays. So does a file you read on its own, from somewhere else or from inside the folder, and a file that
is no longer in the folder: reading your folder again would not bring them back, and Geni needs a sign-in
that lasts a day. The reset says how many facts stay for that reason and where they came from. What you
checked, answered or told the library yourself stays too, and so do the other names you typed with `graph
alias` and the joins you said yes to in `genealogy tidy`. Before it takes anything out, the library saves
a copy in your library's family folder and says where. If a reset is stopped before it has finished,
because the computer went off or you pressed Ctrl-C, the next `genealogy` command finishes it and says so.

`genealogy reset --all` takes out everything the library read for your family, from Geni and web pages as
well. What you checked yourself, the other names you typed with `graph alias` and the joins you said yes to
in `genealogy tidy` stay. Before it asks, it says how many of those facts came from outside your folder and
where they came from, and prints the commands that read each source again: the Geni sign-in first
(`researchzosho records login geni`), then each Geni profile with the largest `--steps` it was read with,
each list of links, and each file you read on its own. It prints the same commands again when it is done.

`genealogy read --again`, with no folder after it, reads the folder the library read last once more, every
file in it, as `genealogy read <folder> --again` does. `--list`, `--skip` and `--only` work as they do for a
folder.

When you type the name of somebody who is not in your library, for example with `genealogy research` or
`genealogy life`, and a copy an earlier reset kept holds them, the library says so, where the facts about
them came from, and the commands that bring them back. It looks in every copy, not only the latest. When
you retired the facts about that name, it says that instead, with the facts' codes.

## 3. Look at the tree, and fix it

### 3.1 The tree

```
researchzosho genealogy tree "髙橋正一"
```

`tree` writes `family-tree.svg`, which any browser opens. Generations run top to bottom. A double line
is a marriage. A dashed line is an adoption, a step-parent or a foster child. A grey line is a claim
nobody has checked yet, a green one is accepted, and red dots mean a dispute. A thin dotted line rests
on a clue only: a family account, somebody else's tree, a web page, not yet seen in a record. The
library's pages show the same tree at `/tree`.

Each box shows the person's latest name. Its second line has the years and, when the person was born
under another family name, that name: `1905 – ? · born 遠藤` for a man born 遠藤健二 who became 森田健二.
Point at a box to see the whole heading. On the `/tree` page, the link "Names and families of …" under
the drawing opens the person's names and the families they belonged to (3.8, 3.9). Any of a person's
names finds them; the links and the commands the library prints use the name it files them under.

### 3.2 A person's life

```
researchzosho genealogy life "髙橋源三郎"
researchzosho genealogy life "髙橋源三郎" --with "髙橋源三郎 (born 1870)"
```

`life` shows what the library holds of one person's life, in order of date, under the person's heading:
their latest name, with the birth name beside it when it was another (`森田健二 (born 遠藤)`). Each line
says what it rests on: a record, something published, or a clue only. A line rests on the best source that any of
your facts gives for it: if your notes and a register both give a birth, the line says "a record".
Between two dates far apart it says so ("nothing between 1898 and 1945"), and at the end it says which
parts of the life nothing is written about: the birth, a marriage, the work. `--with` lays two lives
side by side, year by year, which is how two people of one name are told apart.

A fact whose source wrote it down long after it happened says so, for example "(written down 69 years
after it happened)" for a birth known only from a death record.

A dated line that falls in a year when the person carried another name starts with that name. A book
that calls him Morita Kenji at school in 1920, when he was still 遠藤健二, gives:

```
  1920  as 遠藤健二: went to the village school (1920).  [draft, F-0020]  (a clue only)  file:///family/book.txt
```

How a book writes a person does not date a name: the years come from the names themselves (3.8).
A relative's line names the relative the same way, under the name they carried that year.

At the end, "Names" lists each of the person's names with how it came, its years, its sources and the
other ways it is written, each with where it came from. A spelling that only a tree site gives is a clue,
like any other fact from there. "Families" lists the families the person belonged to, with the command
that opens each (3.9). Somebody with one name, written one way, and no family has neither.

The life also shows the births, marriages and deaths of the person's parents, brothers and sisters,
husband or wife and children that fall inside it, each marked as a relative's event. For a man born in
1850 whose father died in 1870, it looks like this:

```
  1850  was born in 1850.  [draft, F-0001]
  1870  The father Isamu Takahashi died in 1870.  [draft, F-0006, a relative's event]
  1880  was married to Ann Hart (1880).  [draft, F-0002]
```

When nothing is written about the person for many years, these lines show where the family was, which
is where to look for the person's records. They do not fill the gap: only the person's own facts do. A
marriage the library holds under the husband's name is part of the wife's life too.

When nothing gives a person's birth date, `life` shows the years the family's other dates leave for it:

```
  1848  born between 1832 and 1848  [worked out from F-0003, F-0007, F-0011, not a claim]
```

The line is never filed, and it goes away when a birth date is read in. An age a record gives is shown
with the birth years it means: `was aged 42 (1885) (so born between 1842 and 1843).`

### 3.3 What cannot be true

```
researchzosho genealogy check
researchzosho genealogy check accept 3f9a2c "the register gives both births"
researchzosho genealogy check reopen 3f9a2c
```

`check` finds what cannot be true: a death before a birth, a child born after a parent died, three
birth parents, a person who is their own ancestor. These almost always mean two people of one name
were taken for one. Section 3.4 shows how to hold them apart. `check` changes nothing by itself.

A date written with about, before, after or between is a range of years. `check` calls facts impossible
only when no years in their ranges fit together, so a parent who "died after 1845" and a child born in
1848 is not a problem. When only the year an "about" date is written around breaks a rule, `check`
lists the facts as unlikely.

`check` also compares every dated home, move, job and event with the birth and the death. It says when
one parent's children were born over more than 35 years, or with more than 20 years between two of
them, which often means two parents of one name. It names a date in which it finds no year. It notes
when a child was born before the date of the parents' marriage. That is common where a marriage was
registered years after the wedding, and there is nothing to fix.

For a person with no birth date, `check` works out the years the family's other dates leave for the
birth. A parent was at least 12 when a child was born, a person married at 12 or older, and a person was
born before anything they did. When those years cannot meet, for example a grandfather born in 1840 and
a grandson born in 1850, the person between them is probably two people of one name.

A person's sex is kept as a claim when a tree file gives it, when an account calls somebody a father,
mother, son or daughter beside a parent claim, or when a word in a sentence about the person says it (2.3).
The word must name that person: in "my mother's uncle" only the uncle is a man, and in "my mother (Emi
Hale)" the mother is Emi. The same holds for a name: in "her husband Tom Hart" or 妻ハル the name beside the
relation word is the relative's, so the library does not keep it as a name of the person, files the
relation instead where the words say whose relative it is, and says so. "She was known as Ann" stays hers.
`check` uses it. A mother over 55 at a birth is unlikely.
A child born after the mother died cannot be, while a father may have died up to a year before. Two
birth fathers or two birth mothers cannot be. One person recorded as both a man and a woman is two
people of one name, or a relation read the wrong way round.

Some claims are set aside in the family's view: a parent born after the child, or less than 12 years
before, a parent claim whose own words say it the other way round, and a sex claim whose words are about
somebody else while another claim's words say the other sex. A read keeps every date a source gives, even one an older claim
contradicts: a read never drops a fact because of another claim, and the view weighs them. A parent claim
an older read filed from words that say adoptive (婿養子, 養父, "adoptive"), a step-parent, a parent-in-law, a
godparent or a foster parent, with no plain parent word, is read as that relation in the family's view: the
person's page says "read as adoptive: the words say 婿養子", and no question is asked about it. A claim set
aside stays in your library as it was. The family's pages and questions leave it out, and `check` and the person's page
(`researchzosho genealogy names "<name>"`) say why. `researchzosho accept <code>` keeps such a claim all
the same.

A parent the account describes without a name, such as "Kimie Hale's father", is not counted as a third
parent. Nor is a parent written with only part of a name, such as まり beside 森田まり. `check` says which
named parent they may be, and gives the merge command.

`check` also lists names that your sources read two ways, and anyone named as a witness, an informant or
a godparent after their own recorded death. The second usually means the record names another person of
the same name. When two facts about one person differ while both cite the same page of the same source,
`check` says so: the page says one thing, so one of the two was read or copied wrongly.

Each thing `check` lists ends with a code in square brackets. When a thing is right as it stands, for
example a father who really was 60 when his son was born, give the code to `check accept` with your
reason. `check` then lists it at the end, under things already looked at, with your reason and the date.
When one of the facts it is about changes, it comes back as new. `check reopen` with the code puts it
back on the list at once.

### 3.4 One name, two people; two names, one person

```
researchzosho genealogy split "John Ellis" --as "John Ellis (born 1851)" --claims F-0003,F-0007
researchzosho graph merge "Genzaburo Takahasi" "Genzaburo Takahashi" --because "both are children of Isamu Takahashi; both born in 1872"
researchzosho genealogy different "Genzaburo Takahasi" "Genzaburo Takahashi" --because "different fathers in the register"
researchzosho graph unmerge "Genzaburo Takahasi"
researchzosho genealogy tidy
```

`split` holds two people of one name apart: the claims you list move to the second name. Reading the
same text or file again leaves them with the second person, and `genealogy reset` keeps them.
`researchzosho map "John Ellis"` shows the claims with their ids.

When two names may be one person, `check` puts them side by side and lists what else is the same for
both: a parent, a husband or wife, a child, a brother or sister, the place of birth or death, the year of
birth or death, the work. It compares parent with parent and husband with husband, so a father's wife
and a son's mother who share a name are not taken as a sign. It also lists what differs: two
birthplaces, two birth years, or more parents than one person has. The pairs with the most in common
come first. When a claim says one of the two is the other's parent, child, husband, wife, brother or
sister, they are two people, and `check` says so.

In names written in Latin letters, `check` also finds two spellings that sound alike, such as Hale and
Hail, by the Daitch-Mokotoff codes genealogists use for European names. They go through the same
comparison.

Each pair ends with the two commands you can give. `graph merge` makes them one person. `genealogy
different` writes them down as two. The library keeps the words after `--because` either way. A pair
written down as two people is not asked about again, by `check` or by `tidy`. To take that answer back,
give the same `different` command with `--undo` at the end. `graph unmerge` takes a merge back, and the
claims that were about that name are about it again.

`tidy` finds people who are in twice because their name was written two ways, and shows under each pair
what else is the same for both. It never joins a pair whose facts do not fit one person, for example two
birthplaces or two birth years, or names that only sound alike. It lists those pairs apart, with the
commands to join them or keep them apart. When it asks, type yes to join all the numbered pairs, or type
the numbers of the pairs to leave apart, for example 2,5. Those pairs are written down as two people and
are not shown again. Without a question: `researchzosho genealogy tidy --yes --apart 2,5`.

A name is the same name with or without a space between its Chinese characters or kana, a full-width
space too, and in the old or the new form of a character: 山田太郎 finds 山田 太郎, and 髙橋 finds 高橋. This
holds in every command that takes a person's name.

A name typed exactly as the library writes an entry that facts are about is always that person, even
when the same name written another way is a second entry: a grandfather 山田 太郎 and his grandson
山田太郎 are two people, and each is found by his own name. The command says that the other entry exists,
with the command that joins them if they are one person and the command that writes them down as two.
Your library's list of names can also hold one name twice, written two ways, with no fact about one of
the two entries, for example after you typed a name yourself. A command given that name then takes the
entry the facts are about, and says so. `check` lists such a pair, and `tidy` offers to join it, a name
of two characters such as 李明 beside 李 明 too: the empty entry becomes another name of the other one, so
that both ways of writing the name lead to the same person. Neither joins anything without your yes. A
pair you keep apart in `tidy` is written down as two people, like the other pairs, and is not shown
again. When facts are about several entries of a name and
you typed it in none of their ways, the command does not choose: it lists the entries with the years it
knows for each, and you give the command again with the one you mean. `different`, `related` and `life
--with` never take their two names as one entry, and two entries written down as two people never lead
to each other.

### 3.5 How two people are related

```
researchzosho genealogy related "髙橋まり" "髙橋ハル"
```

The library works it out from the claims about parents and marriages. It names the relation (a
grandfather, an uncle or aunt, a second cousin once removed), names the nearest ancestor the two share,
and lists the claims on the way. When an account calls somebody a grandson, a cousin, 曾孫 or a brother,
and the parent claims give a different relation, `check` lists it as unlikely: a generation may be
missing or counted twice. When the tree has no line between the two yet, `check` leaves the pair alone,
and `related` says that it finds no line between them in the claims about parents and marriages, so
they may be related through somebody whose parents are not in your library yet.

### 3.6 The Decisions page

```
http://127.0.0.1:4649/decide
```

The library's web pages have a Decisions page (the Decisions link, beside Who is who), for you and for
anyone you let write to the library. It lists the names that may be one person, then the facts where two
sources give one person two dates or two places, then the people whose web pages wait for your word.

Each answer says what it will do before you press it: which facts move to the other name when you say
they are one person, and which fact becomes disputed when you keep the other. If you cannot tell, press
I cannot tell. The next time you give the command `researchzosho genealogy research`, the search for those
people asks for a record that settles it, also when they were searched for before, and the question
waits in the open questions until you answer on the page. When one of the people it names may be
living, a research given `--skip-living` leaves the question out, as it leaves out every question that
names the living. The page says so.

"One person" and "Two people" each show the command that takes them back. "Keep" shows the two commands
that choose the other fact after all: accept the one it marked as disputed, then dispute the one you
kept. Both facts stay on record either way. An "I cannot tell" question closes when you answer the pair
on the page.

The page also says how many questions about names and families wait, and of which kinds, with a link to
the Who is who page, which asks them one at a time (see 3.10). The home page and the Inbox count them too.

### 3.7 Who may be living

A person counts as possibly living until a death, or a date more than 110 years back, places them in the
past. Any dated fact counts, not only a birth. A christening, a marriage, a job or a home in 1870 places a
person too, and so do the dates of their children, parents, husband or wife. A date with an open end, such
as "after 1910", sets no limit. A range such as "between 1905 and 1930" counts at its latest year. A year
written beside a relation, such as a cousin or a brother, dates nobody: it is usually about something
else. A relative's arithmetic never outweighs a person's own dated birth. When the review finds two years
for one birth and disputes both, the later of the two still counts: the question is which year, not
whether. A death, a burial or a cremation that a tree file or Geni gives without a date is kept as a fact
of its own, and places the person in the past.

The library works this out from the facts each time it reads them. When you dispute a fact, or keep
the other year on the Decisions page, the change counts at once.

Whether a person may be living changes what the research asks. A person who may be living, a child among
them, is asked about their work and public life, never about their death or their marriage records.
`genealogy research --skip-living` leaves the living out: nobody who may be living is searched for or
named in a question. The tree prints a `?` for the death of somebody who has died on a date nobody wrote
down, and nothing for somebody who may be living.

Who sees your library is decided by who may read it. Anyone who may read your library sees everything in
it, the living as well. `researchzosho reader` decides who may read; to keep the library from somebody,
do not give them read access.

### 3.8 Names over a life

```
researchzosho genealogy names "森田健二"
```

A person can carry several names in a life: the name at birth, a name taken at marriage, on adoption, on
entering a family as 婿養子, on becoming head of a family, by a legal change, or a pen or religious name
beside the others. Each name is a claim of its own, with its family name and given name, how it came,
the years it was carried, the fact that caused it (the adoption, the marriage), and its sources. The
kanji, the kana reading and each romaji spelling of one name are the ways that name is written, not
names of their own: 森田健二, もりた けんじ and Morita Kenji are one name, and 遠藤健二 is another.

`names` shows them in the order of the life:

```
森田健二 (born 遠藤)

Names, in the order of the life:
  遠藤健二: the name at birth, from 1905 to 1932. From book.txt (a clue only). [F-0016]
    also written えんどう けんじ (from book.txt, a clue only)
  森田健二: the name he took when he married into the 森田 family as 婿養子, in 1932. It came with the adoption. From book.txt (a clue only). [F-0001, F-0017]
    also written Morita Kenji (from book.txt, a clue only)

Families:
  森田 family: married into it as 婿養子 in 1932, coming from 遠藤 family (広島県安芸郡) (the library worked this out from a parent's family at the birth, as no source says where they came from). [F-0003]
    researchzosho genealogy family "森田 family" shows that family: its heads and its members, with where each came from and went to.
```

The first line is the person's heading: the latest name, and beside it the family name they were born
with when that was another (`Mary Ellis (born Hale)`), or the whole birth name when the given name
changed too. The heading is what the tree, the life, `related` and the research plan show. A bracket that
tells two people of one name apart, such as `(born 1941)`, belongs to the person and stays beside the
heading: `Ann Hart (born 1941)`. A person filed under one word, such as a title, a courtesy name or a given
name alone, is headed by a full name of theirs when your library has one, with the entry's own name after
it: `Mary Hale (Ann)`. An entry of a family name alone keeps its own name, since it is somebody of that
family whom the source does not name. The commands, the links and the checks keep the name the library files the
person under, because that is how it finds them. When the two differ, the page says so.

The library works out from the names which name the person carried in a year. A name runs from its own
year to the year of the next name that took its place. A religious, pen or other name carried beside the
others ends none of them. Where the names give no years, the name a record of that year was written
under counts. A family account or a book is a clue: how it writes a person dates nothing.

Where the other facts settle how or when a name came, the library works it out, and the page says it is
worked out:

- A later family name that the husband or wife carried when they married, beside a birth name of another
  family name, dates from that marriage. This is the same for a man and a woman. A married name a tree
  file gives without a year takes the year of its marriage. A name the husband or wife took only after
  the marriage dates nothing. A record that writes the person under the name before the marriage leaves
  the year as it is, and the library asks you about that record (section 3.10).
- How that name came is what a source's words say: 婿養子, 養女, 入夫, adopted, took his wife's name. When
  no words say it, the library looks for anything that points to more than a marriage: an adoption of the
  person, an entry into that family (entered the Morita family, married into the Morita family), the
  husband's or wife's parents written down as the person's own parents, or the person as the head or heir
  of that family. If there is any, you are asked how the name came, with the year already shown
  (section 3.10). If there is none, the name came with the marriage, and its page says it was worked out.
  To say it came another way, give `researchzosho genealogy who "<the person's name>"`: it asks about that
  person's names, and one answer changes it.
- A name of a family a person entered, where a fact says how (as 婿養子, or by adoption into the family the
  adopter belonged to then), came with that entry: 森田健二, who entered the
  森田 family as 婿養子 in 1932. When that is the only name your library has for the person, it came with
  the entry only when the entry brought the person in from another family (as 婿養子, by 入夫 marriage, by
  adoption or by marriage; becoming the head never does) and every birth parent your library has carried
  another family name. Otherwise nothing says the name came later, and it stays the name the person is
  filed under. A record that writes the name before the entry
  is asked about the same way. A name taken as 婿養子, by 入夫 marriage or on an adoption that a source
  ties to that event, with no year of its own, takes the event's year, unless a record writes the name
  before it. Anybody who married into a family is asked how the name came, with the year of the entry
  (section 3.10).
- The first name, when it is the family name a birth parent carried at the birth, is the name at
  birth beside a later name whose kind and year a source gives or the facts above settle: 森田正二,
  Isamu's son, before he became 髙橋正二 on his adoption in 1940, and Mary Hale, John Hale's daughter,
  before her marriage in 1875. Two names that nothing orders are asked about, because a "son" in a story
  does not say born or adopted.

When the facts point two ways, one fact fits two names, or you disputed an answer that said the same,
nothing is worked out and the library asks you instead (section 3.10). Your answer outranks a book:
after you say an adoption was an ordinary one, the book's 婿養子 no longer counts.

Names in Latin letters are compared letter for letter, without accents and in either order, unless the
person's other names show their language. Then the ways that language is written in Latin letters count
as one name. The romaji rules (Endō, Endo, Endou, Endoh and Endoo are one, and an n' before a vowel may be
left out) apply to a person who also has a name in kanji or kana. A name in Hangul brings the Korean rules
(Gim and Kim; Bak, Pak and Park; Lee, Yi and Rhee), Cyrillic the Russian (Tchaikovsky, Chaikovsky,
Tschaikowski), Greek letters the Greek, ü, ö, ä or ß the German (Müller, Mueller, Muller), å, æ, ø or þ the
Nordic, and pinyin with its tone marks the Chinese (Zhang, Chang and Cheung; Deng Xiaoping and Teng
Hsiao-p'ing). A person known only in plain Latin letters has no language, so an English Lee is only ever a
Lee. A family name in characters is read as the Latin word two or more people of that family agree on
(遠藤健二 written Kenji Endoh and 遠藤ハル written Haru Endoh read 遠藤 as Endoh). The rules are plain text,
one file per language. To add to them, or to add a language, put a file of your own in
`~/.researchzosho/spellings/`, named by the language (`ja.txt`, `ko.txt`, or a new name): a line
`fold o: oh` says two spellings are one, and a line `style plain: ō=o` is a way of writing for searches.
Your lines add to the program's. Which word of a
romanised name is the family name is known from a reading your library holds: the name's kana, a
family's romanised name, or two names of the person that share the given name (Kenji Endo beside Morita
Kenji). Where a reading is known, a given name such as Taro is never read as a family name. Where no
source reads it, as with the romanised form in a tree file, either word may be the family name, so a
question can offer that person for both, and you choose. The same holds for a name your library has only
in Latin letters when both its words can be romanised Japanese, such as Morita Shoichi: a book may write
it family name first or given name first. The order is known only when a source's "Morita, Shoichi", a
family your library knows, a relative's name or a claim with the name's parts says it. Until then a word
of it written alone ("Shoichi's bakery") is offered as that person, and never taken for a family name.

A library from before 0.5.0 keeps its names as they were. The name a person is filed under and its other
spellings are read as one name. An other name under another family name is shown as a name whose reason
is not known yet. A romanised other name goes with the name whose family name a source reads it as (a
family written both 森田家 and the Morita family); when it could be the romaji of either of two names, it
is shown as a name of its own rather than under the wrong one. A given name alone (健二, Mary) and a
name with a middle name more (Mary Ann Ellis) are ways of writing a name, not names of their own. So are a
title or an honorific with a name (Mr. Hart, Sir Tom, Viscount Hart, 遠藤さん, 子爵 遠藤健二), an index's
"Hart, Tom", Jr. or Sr., and the number of a hereditary name (初代 森田勇): none of them is asked about as a
name of its own. A title or an honorific with a family name alone (Mr. Hart, Pastor Hart, Hart-sensei,
遠藤さん), or an initial with it that could be any of two names (T. Hart beside Tom Hart and Ted Hart), is
how a text addresses the person and no name of theirs: a read files no name for it, and the person's page
shows it once under "Also addressed as", with its source. A family name alone that an older library kept among a person's other names ("Hart") is
shown with the name it is part of. `genealogy tidy` offers to take it off, together with a title with one
word such as "Viscount Hart". While it is there, it still leads no fact of your family's sources to this
person: a family name or a given name alone finds a person only when it is the name the person is filed
under, and otherwise stays somebody of its own, whom the library asks you about. When you
join two entries, the other names of the one that went into the other, such as a reading a source gave,
come along. Nothing is rewritten: reading the texts again (`genealogy read --again`) files the names with
their kinds and years.

When you dispute a name, the other names only its own sources gave are taken off the person again. One
that another source gave, or that you added yourself, stays. A source that gives the name with less, such
as a register with no kind and no year beside a book's, is a claim of its own: it keeps the name when you
dispute the book's claim, and it never backs the book's kind and year.

The library's pages show the same at `/person?name=…`, with a link to each family, to each claim and
to the tree. The questions about the person are listed there too; the owner of the library, and anyone the
owner lets write to it, get a link to answer them on the Who is who page.

### 3.9 Families

```
researchzosho genealogy family
researchzosho genealogy family "森田 family"
researchzosho genealogy family 遠藤
```

A family (a house, a line, a clan, a 家) is an entry of its own, written down when a text speaks of it as
a family: the Morita family, 森田家, the Endos. Its label is the family name and the word family, with its
seat in brackets when a source gives one: `遠藤 family (広島県安芸郡)`. A branch (森田分家) is a family of its
own, `森田 branch family`, under the same family name; 森田本家, the main house, is the 森田 family itself.
A family name is not a family: two unrelated 遠藤 families are two entries, told apart by their seats, and
nobody becomes a member of a family because of a surname. When one source gives a family a seat and the
library already has a family of that name whose seat no source gives, the two stay two entries until you
say they are one (3.10).

A person's membership says how they came in (born into it, married into it, adopted into it, as 婿養子,
by 入夫 marriage, by succeeding to it, by founding it), when, how they left and when, and whether they were
its head or its heir. Somebody born into a family who became its head later has two lines: a member from
the birth, and the head from the year they became it. When no source gives the years of a membership, the
person's names place it: the family of the name they were born with, or of a parent at the birth, from
the birth; the family of a later name, from the year that name was taken. The membership of the family a
birth parent belonged to begins at the birth even when its claim carries a later year, such as the year
they left; another family of the same name keeps the year its claim gives. When no source says when
they left it and they were then adopted into another family (as 婿養子, as heir, or by 入夫 marriage),
they left it in the year of that adoption, shown as worked out. A
marriage into another family leaves it open: in many places a person stays in the family they were born
into for life. When no source says which family somebody came from, the family a parent belonged to at
the birth is shown, marked as worked out.

`family` with nothing after it lists the families in your library, with how many members each has.
`family "森田 family"` opens one family. The words a source wrote for it, such as `family 森田家`, open it
too, as long as no other family has that name; when two families do, you get the list of both. A branch
does not count against the family it is a branch of: `family 森田家` opens the 森田 family beside
`森田 branch family`. A family's
page shows its other written names, its seat, the family it branched from and its branches, its founder,
the hereditary name its heads carried, its heads in order with their years, and its members in the order
they came in, each with how they came and left, where from and where to, and the command that opens their
names.

`family 遠藤`, a family name alone, lists every family of that name with its seat, and everybody who bore
the name with the years they bore it:

```
The people who bore the family name 遠藤, and the years they bore it:
  遠藤正一: as 遠藤正一, from their birth in 1875 to their death in 1930.
  森田健二 (born 遠藤): as 遠藤健二, from 1905 to 1932.
```

A person who took the name later bore it only from then on, so 森田健二 is listed under 森田 from 1932.

On the library's pages, Families in the menu opens `/family`. From a person's page you go to a family,
from the family to a member, from the member to another family they belonged to, and back to the
person and the tree. Anyone who may read the library may open these pages. Nothing is changed from them.

### 3.10 Questions about names and families

```
researchzosho genealogy who                   # the questions about names and families first, then who is who on the web
researchzosho genealogy who --list            # what waits, each question with its code
researchzosho genealogy who --answered        # your answers, each with the command that takes it back
researchzosho genealogy who --reopen 3f2a1c   # ask again a question you put off, or take an answer back and ask again
```

Some things only your family can say. The library works out from the facts which of them are in
question, and asks you. It never answers one by itself. It asks only about your close family: you, your
parents, brothers and sisters, grandparents and great-grandparents, and the brothers, sisters, children,
husbands and wives of any of those. It finds them from your own words, what you wrote beside a link ("my
father's father's father"), what you told the library and your own notes, and from there through
parents, children, brothers, sisters, husbands and wives. While the library does not know who you are, it
asks about the people your family's own texts, tree files and Geni profiles name. Each question starts
with who the person is to you ("Walter Hale is your father's father's father's brother."), then says what
each source says, in its own words. The relation is written the same way for everybody, one plain word a
step: father or mother, son or daughter, brother or sister, husband or wife, as the facts say the person's
sex, and "brother or sister" only where nothing says which. Two entries in one question that are the same
relation to you are said once: "Tom and Tom Hale are both your father's father's brother." What is not asked is not dropped: the person's page (`genealogy names`)
shows it under "Not settled", with what the sources say, and `genealogy who "<name>"` asks it all the same.
`genealogy who --list` says how many such things there are. A person you marked by hand in ordinary
research is not asked about. A library with genealogy turned off (`profile disable genealogy`) asks none.

- **Where your notes and a source disagree.** Your note beside a link, what you told the library or your
  own notes give two people one relation and a source gives them another (your note says Jack is Emma's
  father, a page says he is her brother), or your note makes somebody a person's parent and a source names
  that person's parents as others. The question puts the two passages side by side and says which is
  yours. You answer that your note is wrong, that the source is wrong, that one of the people the source
  names is the one your note means, or that you are not sure. This is asked whoever it concerns, because
  only you can correct your own notes.

- **A person written only by a family name.** A book says "Endo's son, Morita Kenji". The library keeps
  the words, files the parent as `森田健二's parent (written only as Endo)`, a member of the Endo family
  ("son" does not say whether Endo is the father or the mother), and lists the people in your library who
  bore the name Endo then and fit as his parent. It never picks the best-known Endo. When exactly one of
  them fits and a second fact agrees, the library links them itself and does not ask (2.3). An older
  library may have an entry "Endo" or "Mr. Endo" that is a family name alone, with a title or without,
  because a source wrote it as a family ("Endo's son", 遠藤家, the Endos) and Endo is somebody's family name
  in your library. Such an entry holds what every source wrote with the name alone, which may be several
  people, so it is asked about one source and one way of writing at a time: "Mr. Endo" in one book, "old
  Endo" in the same book and "Endo" in another book are three questions. Each quotes the first two
  passages and says how many more there are, and its answer moves the facts of all of them to the person
  you choose; "Endo" elsewhere in your sources stays as it is. When the passages are not all one person,
  the answer "They are not all the same person" asks about each passage on its own. The question lists
  six people at most, the likeliest first: those your library already writes that way, those the same
  source names, then those whose facts agree most with the passages. It leaves out anybody the words rule
  out: a woman where they say Mr., Sir, Viscount, Father or 氏, a man where they say Mrs., Miss, Lady or
  Sister (as the person's sex is filed), anybody born after a passage's year or dead before it, a
  description such as "an old servant of the Endo family", and anybody you said is somebody else. You can also
  type the name of somebody else in your library, keep it as one person whose given name is not known, or
  make it the Endo family itself (somebody of the Endo family whom the source does not name, when the
  words write a relative beside it). `genealogy who --reopen` moves the facts back.
- **One person or two.** A record gives one entry the name of another entry; one given name stands under
  two family names and something else agrees (a parent, a husband or wife, a place, a birth year); the
  reading a source gave (もりた けんじ) is, in Latin letters, the name of another entry (Morita Kenji); or
  an entry of a given name alone (健二, or Kenji) has exactly one whole name in your library with that
  given name, also when a source writes that name in Latin letters (Morita Kenji for 森田健二); or an entry
  of one word is a word of exactly one romanised Japanese name whose order nothing in your library says
  (Shoichi beside Morita Shoichi), and then the question does not call the word a family name or a given
  name.
  An entry written by a family name alone ("Kano", what a book wrote by that word, of one person or several)
  is never "another person of that name" beside a whole name, so no such question is asked against it.
  "One person" is offered first. The library never joins two entries because their names look alike.
  An entry written in Latin letters is joined into the one written in characters, because the characters
  say which family, and an entry of a given name alone is joined into the whole name, in whatever
  letters. After you say one person of two entries with two family names, it asks how and when the name
  changed. A tree file whose other name meets an entry in the other word order (Kenji Morita,
  Morita Kenji) is asked about the same way, and so is an entry whose name in characters is written given
  name first, as some trees and older reads of Geni write it (勇 森田), beside the same name written
  family name first (森田勇).
- **How a name changed.** A name whose kind no source gives: taken at a marriage, as 婿養子, by 入夫
  marriage, on an adoption, as heir, on succeeding as head, by a legal change, or only another name. For a
  later family name that the husband or wife carried, the answers are 婿養子 for a husband or 養女 for a
  wife (both when your library does not say which the person is), 入夫, an adoption, and only the name at
  the marriage, then an adoption as heir, becoming the head, a legal change or a will, and only another
  name, eight answers at most. When an heirship is why it is asked, the heir and the head come first
  among those. This is asked when something points to more than a marriage (3.8), for a man and a woman
  alike, also when a tree file types the name as a married name. An adoption as 婿養子 with no marriage
  and no family entered behind it is asked about too; an adoption by a parent whose family name the person
  carries in a name is the entry into that family, the name was taken on it, and nothing is asked. A name
  of the family a step-parent carried is not asked about either. What the facts already explain is not asked: a
  married name with nothing that points to more, which is worked out from the marriage, and the name a
  person carried before a change the facts explain, such as a maiden name beside it, unless the person
  later married somebody of that family name. An older library's other name in Latin letters that could be
  the romaji of either of two names in characters is not asked about on its own: the names in characters
  are. The name an entry is filed under is asked about, in whatever letters, unless it is a title with one
  word or a family name alone, or another name of the person is asked about: that question says "and also
  the name" it is filed under, so two names are one question, not one from each side. The text's own words
  settle how a name came, and nothing is asked: "my Christian name is Paul", "baptised Mary" or 洗礼名 is a
  religious name; "born in Leeds as Tom Hart" or "née Ellis" the name at birth; "adopted by the lord and
  named Kenji" a name taken on adoption; "known as Helen", or a Western given name in brackets beside a
  Japanese one ("Haru (Helen) Morita"), another name carried beside the first. A name written with such a
  name and the person's own names ("Paul Morita") is a form of it. "Mrs. Tom Hale" written for Tom's wife is
  how she was addressed, and a name the words give a thing ("I named the farm Willow Farm") is the thing's:
  neither is a name of the person, and nothing is asked. An index's "Endō, Kenji. See Morita, Kenji" says
  the two forms are one person's and nothing of how the name came, so it raises no question of how. An
  index's "Morita, Kenji (father)" says what the person is to the book's subject: the name is "Morita,
  Kenji", and the note is no part of it. "Yuri Hale (formerly Yuri Ellis)", "Yuri Hale, formerly Yuri
  Ellis", "formerly known as" and 旧姓 say which name came first. When Yuri Hale is the name taken at a
  marriage and no other change of family name is known, Yuri Ellis is the name at birth, worked out,
  carried up to the marriage. When how Yuri Hale came is not known, the page shows Yuri Ellis as the earlier
  name and Yuri Hale as the later, and asks nothing. A name a source writes in kana alone (きゅうろう), for a
  person filed in characters, is a reading of one of their names, never a name of its own: it goes with
  the name in characters it stands beside in the source's words, or with the name the person is filed under,
  and is shown as "also written". Two readings of one name are the question of which is right, as before.
  When you know a name was never the person's, as when an older reading filed a book's editor among the
  writer's names, answer "It was never a name of …": the name goes, the facts that gave it are marked as
  disputed, and `genealogy who --reopen` gives both back.
- **When a name changed.** A name taken later whose year no source gives. The question shows the years the
  records use each name, and you type the year. A name taken at a marriage or an adoption whose date your
  library has is dated by it and not asked about, unless a record writes the name before that date. A
  married name that a source ties to its marriage is not asked about when that marriage has no date
  either: the name came with the marriage, and the marriage's date is what is missing.
- **A birth or an adoptive parent.** Two fathers or two mothers written as birth parents, or a parent who
  carried another family name than the child's name at birth. The answers are birth, adoptive, step,
  foster and in-law. Two fathers or two mothers whom the library holds as possibly one person (two names
  one character apart, which are two people until you say otherwise) are not asked about this way: the
  pair is asked about once, as one person or two, with the children they share and what else agrees, and
  "one person" joins them. It is asked when one of the two, or a child they share, is your close family:
  it decides who that child's parent is, as the question about the child would have.
- **Which family.** A family with no seat beside others of its name: is it one of them, or a family of its
  own? A branch and the family it is a branch of are two families, and are not asked about.
- **A record under a name the person did not carry then.** The record is kept as it is. When it writes a
  name the person took only later, it may have been written later, or the name may have changed earlier.
  When it still writes the earlier name after the change, as records often do (a death recorded under a
  maiden name), it may have kept the earlier name, or the name may have changed later; the year you give
  goes on the later name, and the earlier name keeps its start. The record may also be about another
  person; for that one the answer gives you the `genealogy split` command for that record.
- **How a name is read.** Your sources give one written name two readings. The readings of two names of
  one life, such as えんどう けんじ for 遠藤健二 and もりた けんじ for 森田健二, are not two readings of one name.

Who is who is asked first: a person written only by a family name, and one person or two. A question that
another open question would settle comes after it: whether 勇 and 森田勇 are one person is asked before
which of them is the birth father of the child written with both, and whether a person that a question
about a family name alone lists is one person with another entry is asked before that question. While reading, too, that question is put
first, also when it was waiting before the read.

Each answer says what it will do before you give it. At the terminal, type the number of your answer.
Enter alone skips a question for now: it stays open for the next sitting. `later` puts it off: the
library does not ask it again by itself, and `genealogy who --answered` lists it with the command that
asks it again. `stop` ends the sitting, and what you answered is kept. The same questions wait on the
**Who is who** page in your browser, one at a time, with a button for each answer.

What an answer does is your word, and yours to change. It files claims that come from the question
itself (`told://family-answer/` and the question's code) and are accepted by you, or it joins two entries
or writes them down as two people, with the question as the reason. The claims are filed under the entry
the question was about as its source writes it, so they stay with that entry when a join is taken back
later. A reading chosen for the name an entry is filed under, before a dated later name, is written down
in the list of answers, so the names keep the order of the life. `genealogy who --answered` lists each
answer with the command that takes it back. When you dispute a claim an answer filed, or take a join
back, the question comes back by itself. Each answer ends with `genealogy who --reopen` and its code,
which takes the whole answer back at once and asks the question again: the claims it filed are retired
(a claim that a source you read later says too stays, as that source's fact, and only your answer comes
off it), its join is undone, a claim it disputed counts as it did before, a note it put on a record comes off, a
family, a described person or a name the answer made and nothing else names is taken away, and the
people a question offered are all offered again. When nothing of an answer stands any more,
`--answered` says so.

A name you type that is one of a person's other names is said as such: "遠藤健二" is the birth name of
森田健二 (born 遠藤). An entry that is a family name alone is not offered as a similar name when a name is
not found; the command says what the entry is, and that `genealogy who` asks who it is. Once you have
answered who it is, it is what you said.

**While reading.** At a terminal, `genealogy read` and `genealogy import` ask a question as soon as a
file, a page, a Geni profile or a tree file raises it, with its numbered answers, and wait 60 seconds
for your answer. The reading does not stop for the question: the library goes on to the next file while
the question waits, and prints what it read once you have answered or the time is up. Your answer is
saved at once. Press Enter to leave a question for the sitting. A question that got no answer while the
library was still reading is asked once more when every file is read, and a question that a later file
settles is not asked. At the end the read says how many questions wait. To change the wait,
set it in the config file:

```
genealogy.ask.seconds = 30
```

0 never asks while reading. A read run by a script, in a pipe or by the service never asks and never
waits: the questions are there for `genealogy who`. The librarian chat asks them too, through
`library_who`.

`genealogy check` lists two of these among what it found: ONE PERSON UNDER TWO FAMILY NAMES? and records
written under a name the person did not carry then. It ends by saying how many questions about names wait
for your family's answer, and `genealogy who` asks them. Two heads of a family who each carried its
hereditary head name (名跡) are two people there, however alike their names are.

### 3.11 A summary of your family

```
researchzosho genealogy summary
researchzosho genealogy summary --out family.md
researchzosho genealogy summary --all
```

The summary says what your sources settle about your close family, one person after another, and
where each thing comes from. Read it once after a read: it is the quickest way to see what the library
holds, what agrees, and where your own notes and the sources part.

The people are grouped by how they are related to you, nearest first: you and your brothers and
sisters; your parents; your parents' brothers and sisters; your grandparents; their brothers and
sisters; your great-grandparents; their brothers and sisters; further back; and the other people your
own notes name. Each of
them is in one group only. Husbands, wives and children go with the person they belong to. Close
family is the same as for the questions (3.10), and to show where the family comes from, every
ancestor on your own line is listed too, in every generation, with their husbands and wives, under
Further back. Somebody the library holds nothing about beyond one relation has no block of their own,
and neither has a person a source only describes ("X's father", Xの父) or a tree site left unnamed
("? Ito"): the relative's list names them, the unnamed as "an unnamed brother or sister (Geni)". `--all` adds everyone else the library places in your family, such as your father's
cousins and the brothers and sisters of your great-great-grandparents. People a book only mentions
are not listed. While the library does not know which person is you, it lists the people your
family's own files name.

Each person has a heading with the name they carried last, the name they were born with when it
differs, and who they are to you. Under it, one line per fact:

- the names over the life, each with how it came, its years and its other spellings and readings in
  brackets; a Christian name or a pen name on a line of its own;
- the birth and the death, with the date and the place. Where the sources give two dates, both are
  there, each with its source, and the summary lists them again under Not settled. A source that gives
  less of a date is named with what it gives: `Born 12 June 1940 (Geni; the family book gives 1940).`;
- the parents, husbands and wives (with the year of the marriage), children, and brothers and sisters.
  A relation the library put together, which no source gives in one place, says so:
  `Parents: Tom Hale. (worked out: brother or sister of Ned Hale)`. Where a relation rests on a name
  the library joined to a person from the evidence, the line says that too:
  `(the library joined "Kit" and Kit Hale as one person: probable, the same source names this person in full)`.

Each line ends with where it comes from, in words you know: your notes (the notes in the family folder
you read, unless the folder's notes say somebody else wrote them, when they are that person's notes),
your link notes (what you wrote beside a link), your answer (what you answered the library), Geni, a
book by its title, a Wikipedia page as `ja.wikipedia: <page>`, another web page by its site, and any
other text file by its name.

After the people come three sections:

- **People who are easy to mix up.** Two people of one name, such as a father and a son; two names in
  characters that differ by one character, between two relatives or between two entries that may be
  one person, which the library never joins by itself (a man and a woman are left out: nobody mixes
  them up); two entries the library holds as possibly one person for another reason; one written name
  the sources use for several people; a family name alone,
  such as "Mr. Hale", that nothing identifies yet, with the relatives of that name the same sources
  name; and a given name two of the family carry. Each says what tells them apart: their years, who
  they are to you, and how they are related to each other.
- **Where your notes and the sources disagree.** Both sides, each with its words and its source, the
  same as the question 3.10 asks, and your answer when you gave one.
- **Not settled.** Two dates for one birth, more parents than a person can have, one person written as
  both husband and brother of somebody, the relatives no source names ("No source names your mother's
  parents."), the names of close family no source says how they came by, and who has no birth date.

`--out family.md` writes the summary to a file instead of the screen. On the library's pages, Family
summary in the menu opens `/summary`, with each name a link to that person's page. The summary asks no
model and no web page: it says what the library holds now.

## 4. Decide what you agree with

```
researchzosho inbox
researchzosho accept F-0003 F-0007
researchzosho dispute F-0009 "she was born in Kure, not Hiroshima"
```

Everything so far is a draft. Look at what is waiting, and accept what you know to be right. `accept
--all` takes everything in the inbox, which is reasonable right after reading in your own notes. An
accepted line turns green in the tree. You do not have to decide everything before going on.

When you dispute or retire a fact, the questions on the waiting list that were written with it are
taken off, so the nightly research does not send it as known. The next `genealogy research` writes them
again from what the library holds then, and it does the same for a waiting question an older version
wrote with a fact you disputed.

### 4.1 Facts that rest on one source

```
researchzosho genealogy source community.pdf                               # every fact that rests on this file
researchzosho genealogy source "https://www.example.org/hale-family"       # the same for a web page
```

When you dispute a fact, the library also shows the other facts that rest on the same source, and which
of them have no other source. A wrong year in a book often means more is wrong in that book. `genealogy
source` shows the same for any source, whenever you want to look.

## 5. Research one person at a time

```
researchzosho research ask "What records exist for 髙橋源三郎, born 1872 in 広島県佐伯郡, who went to Hawaii in 1899? What did he do in life?" --depth --genealogy
```

`--genealogy` asks for genealogy mode. The library uses genealogy mode only when you ask for it: with
`--genealogy`, the box "Family history (genealogy mode)" on the Research page, `field: "genealogy"` from a
program, "in genealogy mode" in the chat and then a yes when it asks, or the `genealogy` commands in this guide. A question that looks like
family history and is sent without it is ordinary research. At a terminal the library asks first, `Use
genealogy mode for this question? (y/N)`; Enter is no. The rest of your library is not changed by
genealogy: only the family's own texts and files, and the runs you asked for in genealogy mode, are read
as family history.

Name the person, the place and the years. A family search (`genealogy research`, below) writes the
questions itself, the way a genealogist does: one short question for each thing the library does not
know yet. Who were the parents? When and where were they born? Whom did they marry? Which facts, known
so far only from a family account or somebody's tree, does a record confirm? What did they do that was
written about? Each question is finished when it is answered, and the report opens with the answers:
for each question, the answer with its record, or not found with where it looked, or the records
disagree with both sides. A question that goes on forever is how research goes on forever. The run:

- takes those questions, one worker to each, and searches the collections that can hold a person of
  those years (a collection of scans that ends in 1970 is not searched for somebody born in 1973)
- splits the work by person and by kind of record
- searches collections of records by name, as well as the web (the list is in section 7)
- ties a record to your person only when a second detail agrees, and lists the ones it could not tie
- keeps names and dates as the record wrote them
- ends with **Identity** (which records belong to whom, and why), **Record collections searched**
  (where it looked, and the searches that found nothing), and **Records to request** (what only the
  family can get, and how)

The question gives the person's dates the way your library holds them ("born about 1850") and asks for
the exact year when the library has only an approximate one. The record collections are chosen for the
whole range. A person with no birth date gets the years worked out from the family, marked as worked
out, so the searches are held to those years too.

A person can carry more than one name over a life: a woman who took her husband's name, a man who
entered his wife's family as 婿養子, an heir who took a new name. A record is written under the name the
person carried when it was made, so the question gives each name with its years and how it came:
"named 遠藤健二 at birth, until 1932; named 森田健二 from 1932, on entering the 森田 family as 婿養子". It
asks for the records before 1932 under 遠藤健二 and for those from 1932 under 森田健二, each in every
written form: as written, in modern characters, the kana reading when a source gave one, each romaji
spelling (Endō, Endo, Endou, Endoh, Endoo) and both name orders. A name in another language's own script
or letters is searched in each way that language is written in Latin letters: a Korean name in the
Revised Romanization and in McCune-Reischauer, a Russian one in the English, German and French ways. A
name that the sources give only in Latin letters is searched in the order they write it. The record of the change itself, such as a
marriage, an adoption or a register's entry line, names both, so the question also asks for the earlier
name searched together with the later family name: 遠藤健二 together with 森田 or 森田家. The characters
decide which family a record is about. A match in romaji alone is a clue to follow to the record.

The questions use what the library has learned from earlier searches. Once searches have been made for
a person with several names, the question names only what they left out: a name no search used, with
the years to search it for and the places already searched, and a change no search carried both names
of. When every name and every change has been searched, the question is done. A record that says a
parent is unknown gets its own question, in the record's words. The people a record names beside the
person, such as a witness or a godparent, are given as leads.

A run takes from a quarter of an hour to an hour. `researchzosho jobs` shows how far it is, and the chat
tells you when it is done. Read the report with `researchzosho export <its id> --md` or on the library's
pages. The report is a draft too: its claims arrive in your inbox, and you decide them as in section 4.

Read a report the way you would read a careful stranger's work. Sentences marked "not supported by
the cited source on check" are ones the program checked against the page and could not confirm.

Ask about people who have died. A research run sends names to search services as queries.

### 5.1 The research log

```
researchzosho genealogy log "髙橋源三郎"
```

Every search a run makes is written down by the library: where it looked, the exact words, the years,
and how many results came back. That is the research log a genealogist keeps, and the next run for the
same person is shown it, so that it searches where nobody has looked and with a spelling nobody has tried.

A line that ends in "did not answer" is a search that could not reach its collection that day: the
service was down, blocked, or over its limit. That says nothing about what the collection holds, so the
next run for the person makes that search again. The next run is also told what no search has used yet:
the written forms of the name, the years of the person's life, and the record collections nobody has
searched for them.

For a person who carried more than one name, the log names them with the name at birth beside the
latest one, `森田健二 (born 遠藤)`. Each line ends with the name the search used and the years the person
carried it, such as `[name: 遠藤健二, 1905–1932]`. A search that used both names, which is how the record
of a change of name is found, ends with `[both names]`, and one that used none of them with
`[none of the names]`. The library works this out from the words of each search, so nothing new is
written down. The next run is told the same thing name by name: the written forms of each name no search
used, the years of each name no search was held to, and each change of name no search carried both
names of.

### 5.2 The whole tree at once

```
researchzosho genealogy research
researchzosho genealogy research --list
researchzosho genealogy research "髙橋源三郎" "山本ハル"
researchzosho genealogy research "髙橋まり" --family --up 6 --down 3
```

When the tree has more than a few people, let the library write the questions. With no name, it goes
through everybody in your family and writes one question for each person, from what your claims already
say: the dates, the places, the parents, the spouse. A person at the edge of the tree, whose parents or
children are not known yet, is also asked about those. `--list` shows you the questions without starting
anything.

Then it shows you its plan: the people it can research, numbered, the people who have died first and among
them the ones your library knows least about, so a thin side of the family comes before a well-documented
one. Each line says what is not known yet about that person, such as their parents, when and where they
were born, or whom they married. The library asks which of them to research now:

```
Which of them should the library research now? Type their numbers with commas between them, for example 1,3. Type all for everybody on the list. Or just press Enter to start nobody now:
```

Type the numbers of the people to start with, for example `1,3,5` (a range such as `2-4`, `2 - 4` or
`2〜4` works too), or `all`. Enter, or no answer, starts nobody. An answer the library cannot read, such
as a number that is not on the list, is asked again. When somebody is left, it asks one more thing: whether to put the others on the
nightly waiting list, where the library takes two a night by itself. Enter is no. Nothing starts and
nothing waits unless you say so. A person already on the waiting list is marked on the plan: they stay
there, and picking them starts them now and takes them off the list. A person whose last search was
stopped or failed is on the plan again, marked with that search's code and day, and a person who waits for
your answer on who is who is marked too. Nobody is looked up on the web before you pick: the library looks
up the people you picked, and the people you put on the waiting list, and asks you about them then. At
the end the command says what happened to the waiting list: who was put on it just now, whose question on
it was written again from what the library holds now and stays on it, and who was on it already.

A script, or a command in a pipe, has nobody to answer. Then the library prints the plan and the commands
to start the people you choose, and starts and queues nothing. `--now 3` starts the first three people on
the plan without asking. `--queue` puts the people you did not start on the nightly waiting list without
asking.

Name a person, and the library researches that person, not their relatives. Several people work the
same way, each name in its own quotation marks or with commas between them. For each person you named
the command says on one line what happened: started now, with the search's code; already running, with its
code; already researched, with its code and the day, and that `--again` researches them once more; on
the nightly waiting list and now started and taken off the list; or not found, with the names in your
library it could be. A person you name is searched for even when they may be living, because you named
them, and the command says so. What their questions say of anybody else follows the other choices: with
`--skip-living` they name no living relative.

Add `--family` to go through the relatives of the people you name as well: six generations up and three
down, or as far as `--up` and `--down` say. The people you named are started, and their relatives are
shown as a plan to choose from, as above. To work on one side of the family only, start from a person on
that side, such as your mother's father. (`--only`, which older versions needed for the people named
alone, is what the command does now, and is still accepted.)

Somebody who has an encyclopedia page of their own gets one more thing in their question: the pages that
mention them. An affair or an event a person had a part in is usually told on its own page, and their own
page gives only the office and its dates. The library asks the encyclopedia which pages link to theirs,
picks the ones that tell of something the person did, and hands them to the search as leads. The command
says when it is doing this. It takes up to a minute for each such person, and the list is kept for thirty days.

The living are asked about as well, children among them, because a family is not only its dead. For them
the question is what public sources say of their work and public life. Their names go out to search
services as queries, as they would if you searched for them yourself. `--skip-living` chooses whom the
research searches for: it keeps to those who have died, and names no living person in a question.

The runs you start are in-depth runs, one after another. The people you put on the nightly waiting list
wait in the open questions, and the nightly research takes two a night. `researchzosho questions budget 6`
raises that.

A tree does not grow six generations in one pass. The run on a great-grandfather is what finds his
parents' names, and only then is there somebody new to ask about. So: let the runs finish, decide their
claims in the inbox, and give the same command again. People already researched are left off the plan, and
the new ones are on it. A search that was stopped or failed does not count, and a person named after such
a search is started again without `--again`. A large family takes days or weeks of nights, not an afternoon.

### 5.3 What a person did

```
researchzosho genealogy life "髙橋源三郎"
```

Dates of birth and death are the frame. What people want to know is the life in between: the offices,
the honours, what someone built or wrote or invented, the public events they had a part in. The reader
keeps these as it reads your material, research runs look for them, and each report has a **Life**
section with a person's events in order. `life` shows everything the library holds of one life.

Each line is a claim with its date, how far it has come (draft, accepted, disputed) and where it is from.
A line you know to be wrong is disputed like any other claim.

### 5.4 Who is who on the web

```
researchzosho genealogy who                  # go through the people who wait
researchzosho genealogy who --list           # what you said, and who still waits
researchzosho genealogy who "髙橋まり"        # answer again for one person
researchzosho genealogy who --find           # only look everybody up, ask nothing yet
```

`genealogy who` first asks the questions about names and families that wait (see 3.10), then the people
whose web pages wait. With a name, it asks both kinds about that person only. Each person is shown under
their names over a life: WHO IS 森田健二 (BORN 遠藤) ON THE WEB?

A name is shared by many people. When you type a relative's name into a search engine you get your relative,
and an actress, and a translator, and you know at a glance which is which. The library does not, and no page
says whose child anybody is. So before it starts the search for a person, `genealogy research` looks that
person up by name and asks you. It does this only for the people it is about to research: the people you
name, the people you pick from its plan, and the people you put on the nightly waiting list. A plan that is
only shown sends no name anywhere.

For each person it shows the people the web has under that name, each with a line about who they are, their
pages, and anything those pages say that your family's facts also say (a year, a place, a parent's name).
The entries whose pages name one of the person's relatives by full name come first: a parent, a husband
or wife, a child. A relative counts once however many of their names a page carries. A family name alone
never counts, because strangers share it. You answer with:

- the number of the one who is your relative. If two entries are the same person, type both: `1,3`
- `none` if none of them is your relative
- `later` if you cannot tell now. The searches go on without it, and nothing found under the name alone is taken as theirs. The person is not asked about again by itself: to answer later, type `researchzosho genealogy who "<name>"` or open them on the Who is who page
- `tell` to say something about the person in your own words: their work, where they live, a school. It goes into the next search as you wrote it, and is filed as your account
- `stop` to end. What you answered is kept

The same questions wait in your browser, on the library's page **Who is who** (`http://127.0.0.1:4649/who`),
where the pages are links you can open and each answer says what it will do before you give it. The
Inbox says how many people wait. You can do some in the terminal and the rest in the browser a day later.

The librarian chat asks the same questions. Say "go through the people who wait", and it shows each
person's entries with their numbers and pages. Answer in your own words ("the first one, and the one from
Cambridge is me too"), and tell it anything you know of the relative: it keeps that as you said it.

A search for a person starts from the pages you confirmed and leaves the other people of the same name out.
No search is started for a person who still waits for your answer, and nobody who waits for it is put on
the nightly waiting list. Such a person is on the plan, marked as waiting for your answer on who is who:
answer, and pick them. People who have an encyclopedia page of their own are not asked about, because that page
already says who they are. The looking up sends each name to a search service, the same as typing it into a
search engine, and takes about half a minute for each person. Two pages whose addresses differ only in a
record number, such as `?id=12` and `?id=13`, are two pages.

## 6. The records only you can get

Some records are closed to everyone but the family. A run will tell you which, but the asking is yours.

**Japan.** The family register (戸籍) is the backbone, and it is not online anywhere. You, your spouse,
and your direct ancestors and descendants may request it. Since March 2024 any municipal office can
issue the whole direct line in one visit (広域交付), in person with photo identification. The oldest
register you can get is the 1886 format (明治19年式), which usually reaches people born late in the Edo
period. Closed registers are kept for 150 years and some have already been destroyed, so ask soon.
Before the registers there are the temple's death register (過去帳), graves, and for samurai families
the domain's rank lists (分限帳).

**United States.** Birth, marriage and death certificates come from the state or county. Military
service and pension files, naturalization papers and passenger lists are at the National Archives.

### 6.1 Reading a record in

```
researchzosho genealogy read register-page-1.jpg --by "戸籍, 廿日市市役所, issued 2026-10-02"
```

When a record arrives, photograph it and read it in like anything else.

A register writes many dates against the one before it: 同日, 同月十日, 同年, 翌年, 前年. Each of these
takes its year from the date written before it in the same entry. The fact keeps the words as the
register writes them, with the year beside them, and says which date the year was worked out from.

An age written in a record (42, 42歳, 享年73, 数え年5歳, 3 months) is kept as a fact of its own and dates
the birth. An age counted the old way (享年, 数え年) counts the year of birth as 1, so the birth can be a
year later than the age alone suggests.

### 6.2 Checking what the model read

```
researchzosho genealogy transcript "register-page-1.jpg"                        # the model's reading
researchzosho genealogy transcript "register-page-1.jpg" --accept               # the reading is right
researchzosho genealogy transcript "register-page-1.jpg" --text > page1.txt     # save it to correct it
researchzosho genealogy transcript "register-page-1.jpg" --from page1.txt       # keep your corrected text
```

The library keeps what the model read in a picture as the picture's transcript. Until somebody checks
it, each fact read from the picture says it was checked against the model's reading only, and
`researchzosho inbox` marks it. A □ is a character the model could not read.

Look at the reading beside the picture. If it is right, accept it. If it is not, save it to a file,
correct it in any text editor, and give it back with `--from`. `--edit` opens the text in your editor
instead, when one is set.

The library then checks the words of every fact read from the picture against your transcript, and
lists the facts whose words are not in it any more, with the command to stop using each one. To read the
facts your corrected transcript gives, read the picture again with `genealogy read`. The library reads
your transcript and does not ask the model again.

A name with a character nobody could read, such as 髙橋□三郎, is kept as the register writes it. The
library never joins it with another name by itself and never searches the web for it. `genealogy check`
still lists it beside a name it may be, for you to decide.

### 6.3 A question that waits for a record

```
researchzosho genealogy hold "髙橋源三郎"
researchzosho genealogy hold "髙橋源三郎" 1 --until "the 除籍謄本 asked from the town hall"
researchzosho genealogy hold "髙橋源三郎" --release 1
```

While you wait for a record, keep the question it answers out of the searches. The first command lists
the questions the library asks about the person, numbered. The second holds question 1. The words after
`--until` are kept with it, with today's date. A held question is not searched for. `genealogy research`
lists it under records to request, so you can see what is waiting and since when.

When the record comes, read it in, then let the question go with the third command. The number after
`--release` is the question's number in the list of held questions.

### 6.4 Sites the library cannot search

```
researchzosho genealogy research --list
researchzosho genealogy log "Tom Hale"
researchzosho looked add "Tom Hale" --where familysearch --what "Tom Hale born 1850"
```

FamilySearch, Ancestry, MyHeritage, Geneanet, Find a Grave and CompGen have no search the library can
use. For each person, `genealogy research --list` and `genealogy log` print the address of a search on
each of them, filled in with the name, the years and the birthplace your library holds. Each line says
whether the site is free, free with an account, or needs a paid subscription. Open the address in your
browser. The library sends nothing.

These sites ask for the family name and the given names apart. The library takes the family name from a
"Family, Given" form, or from the word the name shares with a relative. A name the library holds only in
Latin letters is taken as given names first. A person the library holds only in kanji gets no links,
and neither does a person who may be living.

A record is indexed under the name the person had when it was made. So a person who carried more than
one name gets one address for each name at each site, followed by the name and the years it was carried:
`FamilySearch, under the name 遠藤健二 (1905–1932)` and `FamilySearch, under the name 森田健二 (from 1932)`.
The family name in each address is that name's own: the family part a source gave for it, or the word it
does not share with the person's other names (Endō beside Morita, since Kenji is in both).

When you have searched a site and found nothing, write it down with `looked add`. The next search for
that person is shown the line, as a place somebody has already looked.

## 7. The collections a run searches

```
researchzosho records                          # the collections, and which need a key
researchzosho records test <source> <a name>   # tries one now
```

| collection | what is in it |
|---|---|
| `ndl-fulltext` | about 280,000 Japanese books searched inside: 人事興信録, 紳士録, 職員録, local histories |
| `japan-search` | the catalogues of some 320 Japanese archives, museums and libraries |
| `ndl-search` | the catalogue of Japan's national library and the prefectural libraries |
| `cinii` (key) | Japanese papers, dissertations and bulletins back to the Meiji journals |
| `wikipedia-ja` | articles on people and families: a starting point, follow the footnotes |
| `loc-newspapers` | US newspapers, 1756 to 1963, searched by their full text |
| `densho` | Japanese American families: photographs, camp newspapers, letters, oral histories |
| `internet-archive` | scanned books of every country searched inside: local histories, directories |
| `serpapi-inventor` (key) | every patent under an inventor's name, from every patent office, back to 1790 |
| `epo-inventor`, `epo-applicant`, `epo-query` (key and secret) | the European Patent Office's collection of the world's patents, by the inventor's name or by the firm that applied. Free: register at developers.epo.org, add an app, and give its two values: `researchzosho records key epo-inventor <key> <secret>` |
| `serpapi-patents` (key) | patents by the words in them: an invention, a firm, a place |
| `gallica`, `delpher`, `nb-no` | newspapers and books of France, the Netherlands and Norway |
| `europeana` (key) | European archives, libraries and museums in one search |
| `wikitree` | a shared family tree anyone may edit: a lead, read the sources a profile cites |
| `geni` (sign-in) | a shared world tree: profiles with dates, places and occupation. `researchzosho records login geni`, once a day |
| `wikidata` | whether a person is already written about, with identifiers |

**The ones marked (key)** need a free key of your own. A key is a password a service gives you so it
knows who is searching. You sign up on the service's site, copy the key, and give it to the program once:

```
researchzosho records key cinii <your key>
researchzosho records test cinii 髙橋正一
```

`researchzosho records` tells you where each key comes from. SerpApi's free plan is 250 searches a month,
and each search a run makes there is one of them.

**Geni needs a sign-in**, which lasts a day. The first time, register a "desktop" application on Geni's
developer page (it is free and takes a minute; `researchzosho records login geni` tells you the address)
and copy its key.
Then:

```
researchzosho records login geni --app <the application's key>
```

It shows an address. Open it in your browser while you are signed in to Geni, approve, and paste the
address of the page you land on back into the terminal. After the first time, `researchzosho records
login geni` is enough. If a relative keeps the family's whole tree on Geni, the GEDCOM file in section 2.4
brings it in at once. The sign-in is for reading single profiles by their link, and for searching Geni's shared tree.

Your country is not here? Add its newspapers or archive yourself, without waiting for a release. The
form is in [LIBRARIAN_HOWTOUSE.md](LIBRARIAN_HOWTOUSE.md), under "Record sources".

## 8. Taking the tree elsewhere

```
researchzosho genealogy export "髙橋正一" > family.ged
```

`export` writes a GEDCOM file, which every family-tree program reads. Section 2.4 reads one in.

The export follows the family only: parents, children, husbands and wives, brothers and sisters. It does
not follow a town two people shared. Each fact carries its sources. A draft says it is a draft, and a
disputed fact is left out. Open questions about a person go into the file as research notes.

The export writes everyone in the family, the living too, with their facts and their sources. Whoever
you give the file to can read all of it.

Every name a person carried is written, the latest first, because that is the one family-tree programs
show. Each has its kind, its years, and its other written forms: the romanised form as `ROMN` and the kana
reading as `FONE`. An adoption is written as an `ADOP` event with its date, its kind and the parent who
adopted. The families a person belonged to are written as family records of their own.

GEDCOM 5.5.1 has no place for some of this, so the file uses a few tags of ResearchZosho's own, and a note
at the top of the file explains them. A kind GEDCOM has no word for is written in the name's `TYPE` as the
source's own words when they say how the name came (婿養子), or else as the word `_NAMEKIND` uses for it
(`mukoyoshi`, `succession`). ResearchZosho
reads them back, so a file you export and import again gives the same names and families, and imported into
the library it came from, it adds nothing that is already there. Other programs keep them or leave them
out. You can write them in a file of your own too, for example to give your tree the families a koseki
register shows.

| tag | where | what it says |
|---|---|---|
| `_NAMEKIND` | under a `NAME` | how the name came: `birth`, `marriage`, `adoptive`, `mukoyoshi` (婿養子, adopted by the wife's parent and married into the family), `nyufu` (入夫 marriage), `succession`, `legal`, `taken-back`, `imposed`, `farm`, `immigrant`, `religious`, `art` or `aka` |
| `_NAMEDATE` | under a `NAME` | when the name was carried: `FROM 1932`, or `FROM 1905 TO 1932` |
| `_NAMEEVENT` | under a `NAME` | the event the name dates from: `ADOP`, `MARR`, or `_SUCC` for becoming the head of a family |
| `_HOUSE` | a record of its own (`0 @H1@ _HOUSE`) | a family: a house, a line, a clan or a 家, with its `NAME`, its seat (`_SEAT`) and the family it is a branch of (`_BRANCH @H2@`) |
| `_MEMBER` | under a person (`1 _MEMBER @H1@`) | a membership of that family, with its `DATE` (`FROM` and `TO`), how the person came in (`_HOW`: birth, marriage, adoption, mukoyoshi, nyufu, succession or founding), how they left (`_LEFT`: marriage-out, adoption-out, branch, death, divorce or adoption-ended) and their role (`_ROLE`: head, heir or member) |
