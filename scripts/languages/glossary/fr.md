# French (fr) glossary

## Address and style

- The reader is addressed as **vous**. Buttons and menu items are infinitives ("Enregistrer", "Supprimer", "Rechercher").
- Typography: « … » with U+00A0 inside; U+00A0 before `:` `;` `?` `!`. A plain space (never U+00A0) where the
  neighbour is a placeholder `{0}`, a tag, a backtick span, a path, a setting name or a command; no space added before a colon
  that belongs to one of those (`Authorization: Bearer`, `http://`, `/prefs set`).
- Apostrophes are plain `'`. No inclusive middle-dot forms; neutral wording ("la personne qui gère la bibliothèque").
- Language names are lowercase (they are filled into sentences: « Toujours lire en {0} »).
- Before a placeholder: no article, contraction or agreement that depends on what arrives. Colon forms
  ("Terminé : {0}.") or the noun before it in guillemets (le sujet « {0} »). Dates arrive as placeholders in their own
  form, so never "le {0}"; "Terminé : {0}" instead.
- Seconds: " s" → " s" (no-break space, "12 s").

## Recurring terms (one word each)

| English | French |
|---|---|
| library | bibliothèque |
| the Librarian | le Bibliothécaire |
| shelf / the shelves | étagère / les étagères ("Your shelf" = « Votre étagère ») |
| subject | sujet |
| report | rapport |
| question / open question | question / question ouverte |
| source | source |
| claim | affirmation |
| research (what the library does with a question) / research run / to research | enquête / enquête (« l'enquête {0} ») / enquêter |
| run (generic: a translation, a merge; the /jobs page) / Runs | tâche / « Tâches »; a saved search "runs" = s'exécute |
| search (look for listings, openings, words, pictures) | recherche (verb: rechercher); "Search" button « Rechercher » |
| saved search (serial) | recherche enregistrée |
| seek | essai (« Essais » page; « fusionner les essais ») |
| merge | fusion / fusionner |
| field | domaine |
| goal | objectif |
| dossier | dossier |
| bridge | passerelle |
| frontier model | modèle de pointe (the word "frontier" appears nowhere else) |
| bookmark | favori |
| inbox | boîte de réception |
| Changes / What changed | Changements |
| Keep (the keeper's page) / keeper | Gestion / la personne qui gère la bibliothèque |
| Owned | Objets |
| the tally | le décompte |
| housekeeping / nightly maintenance | la maintenance de nuit |
| seat (a model's role) | poste |
| drive (the model the library runs on) | modèle principal |
| entry (model entry, glossary entry) | entrée |
| tier (translation) | niveau |
| glossary | glossaire |
| note (the note beside a translation) / the notes at the end | la note jointe / les notes en fin de document |
| check / checked / the checker (model) | vérification / vérifier / le modèle qui vérifie |
| claim (patent) / abstract (patent) | revendication / abrégé |
| folder | dossier (the French word for both; context separates it from a dossier) |
| settings / setting | Réglages / réglage |
| delete / remove / drop / retire / put away / park | supprimer / enlever / écarter / retirer / ranger / mettre en attente |
| sign in / sign out | se connecter / se déconnecter |
| worker (parallel) | exécutant |
| Past / Updates | Historique / Mises à jour |
| reading levels Simple / Familiar / Original | Simple / Initié / Original |
| access levels read / write / deny | lecture / écriture / refusé (« Accès : lecture ») |
| claim states accepted / disputed / retired / draft | acceptée / contestée / retirée / brouillon |
| unit (of a translation) | segment |
| pass (of a translation) | passe |
| key / token / passkey | clé / jeton / clé d'accès |
| model / host / hosted | modèle / hôte / hébergé |
| turn (a model turn) / turn (a chat message) / conversation / chat | tour / message / conversation / chat |
| reader / writer (access) | lecteur / personne qui peut écrire (role words: lecture, écriture) |
| listing (home) / opening (job) | annonce / offre |
| employer | employeur |
| card (home search) / graphics card | fiche / carte graphique |
| record (public records of a home, an employer) / record (a catalogue or report record; patent record) / records (purchases) | registre / notice / relevés |
| working life | parcours professionnel |
| career (page) | Emploi |
| home search / job search | recherche de logement / recherche d'emploi |
| dispute / retire / accept | contester / retirer / accepter |
| forecast / story / perspectives / established | prévision / récit / points de vue / établi |
| preferences / reaction | préférences / réaction |
| proposal / suggestion | proposition / suggestion |
| notice (recall) | avis de rappel |
| candidate / pool | candidat / vivier |
| patent family / feature | famille / caractéristique |
| show / episode / feed / transcript | émission / épisode / flux / transcription |
| channel / frame / thumbnail / storyboard sheet | chaîne / image / miniature / planche |
| cap / allowance | plafond / quota |
| score (Brier) | score |

## Rule 6: what a person types in the chat

| English | French |
|---|---|
| translate this file into Japanese | traduis ce fichier en japonais (the chat is addressed with tu-imperatives, as French chat assistants are) |
| quick | rapide |
| checked | vérifiée |
| reviewed | relue |
| thorough | approfondie |
| with the notes at the end | avec les notes à la fin |
| no notes | sans notes |
| yes / no | oui / non |
| this file | ce fichier |
| Find out … | Renseigne-toi sur … |
| what happened | que s'est-il passé |
| show it | montre-le |
| find me some pictures of … | trouve-moi des images de … |
| render Acme Corp as アクメ社 in Japanese | traduis Acme Corp par アクメ社 en japonais |

The terminal's (y/N) prompts stay "(y/N)"; the program already accepts oui/non beside y/yes.

The phrases that name a model ("using deepseek", "translated by kimi", "checked by kimi", "have kimi k3 do a thorough
check", "checked by the strongest other model") stay in English and are said to be typed in English.

## Rule 4: counts

French uses the singular for 1 and the plural for 2 and more; the program gives the second form to 0 too. The pairs
("{0} claim" / "{0} claims") keep the natural order, "{0} affirmation" / "{0} affirmations", because most of them are glued
into a larger sentence ("{0} provenant de {1}.", "{0} votre examen.") where a noun-first form would break it; "0 affirmations"
is the usage French software and readers accept. Where a single string carries a count with an agreeing word after it
("{0} accepted", "{0} refused", "{0} of these are second copies"), the noun or participle goes first so nothing must agree
with the number: « acceptées : {0} », « Doublons d'une même question parmi elles : {0} ». "{0} recall notice(s)" and
"{0} time(s)" use invariable words (avis, fois). Verb pairs agree: "{0} affirmation attend" / "{0} affirmations attendent".

## Second round

- Yes/no questions no longer carry "(y/N)": the program adds the hint, which for French is **(o/N)** (words file). "o", "oui",
  "d'accord", "ok" are yes; "n", "non", "non merci" are no. The line above about "(y/N)" no longer holds.
- Language names: the ten keys ("Japanese"…) now stand alone (lists, the language switch), so they are capitalised: Japonais,
  Anglais… Inside a sentence the program takes the first entry of `lang.<xx>` in the words file, lowercase: « écrit en japonais ».
- Field names inside « Utiliser le mode {0} pour cette question ? », bare noun, no article: carrière (not "emploi": « mode emploi »
  reads as « mode d'emploi »), podcasts, organisation, science, personne, généalogie, vidéo (youtube), livres, logiciel, achats,
  brevets, droit, logement, savoir-faire (crafts: any craft, hobby, sport, trade). « in genealogy mode » is typed in English.
- Translation tiers inside a time: « environ 5 minutes au niveau vérifié / rapide / relu / approfondi »; the typed words stay
  « vérifiée », « rapide », « relue », « approfondie ». A model entry's tier (A, B, C) is also « niveau {0} »; its cap « plafond »,
  its reasoning effort « effort ». The model that checks a translation: « le modèle qui relit ».
- "the default" (model entry) = « le modèle par défaut »; "make X the default" = « mets X par défaut ».
- Pictures: kinds person / place / thing / animal / statue / painting / coin / ship = personne / lieu / chose / animal / statue /
  tableau / pièce de monnaie / navire. A kind is never after an article that would have to agree: « Recherche : même {0} que sur
  votre image », « Même {2} : 2 sur 6 », « la catégorie {0} ». Aspects: « l'aspect et le contenu », « l'ambiance et l'aspect ».
  Tallies are noun-first and do not change with the number: « Semblables à la vôtre : {0} sur {1} ; sans certitude : {3} ».
- A run's stage inside a sentence: the lowercase noun forms (« lecture, {0} sur {1} parties terminées »), counts as « {0} sur {1} ».
- Buttons split from a page name: Keep = « Garder », Ask = « Demander l'accès », deny = « Refuser », set = « Définir »; the
  import page Read in = « Importer »; career columns Reading = « Lecture », Read = « Offres lues ».
- "search place" (a count shared with a search service) = « service de recherche »; "prior art" = « l'état de la technique ».
- "; and " between two kinds of excluded material is « , et »: a string may not start with a blank, and French wants one before « ; ».
- Helplines: numbers, service names and hours exactly as given (16:00, 8:00), nothing added.

## Third round (the pages that were still English)

- The badges of a claim's source: registre public, publication propre, témoignage, fournie, tiers, œuvre originale.
- Told is one word everywhere (heading, counts, button): Témoignages. Reading = Lecture; Story = Récit; Pattern of life = Habitudes de vie; How resolved = Identification; tilt = penchant.
- A date after "said" or "written" takes a colon or parentheses, never « le {0} ».
