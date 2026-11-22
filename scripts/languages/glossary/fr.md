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

## Fourth round (the account service's pages, 0.8.0)

| English | French |
|---|---|
| account service (several accounts, each with a library, on one ResearchZosho) | service de comptes ; "runs under an account service" = fonctionne sous un service de comptes |
| whoever runs this service (the operator, not a library's keeper) | la personne qui administre ce service (« gérer » stays the keeper's verb) |
| join link | lien d'invitation |
| let someone in / is let in (the keeper's act on the account page) | donner accès (« Donner accès à quelqu'un », button « Donner accès », « {0} a désormais accès à la bibliothèque « {1} » ») |
| the people of a library | membres (« Membres de « {0} » ») |
| You keep it / You read it and write to it / You read it | Vous la gérez. / Vous pouvez la lire et y écrire. / Vous pouvez la lire. |
| What they may do + read / read and write (label and its choices) | « Accès accordé » + lecture / lecture et écriture (a noun label, since the choices are nouns) |
| Removed. (a person taken out of a library) | Accès retiré. (« enlevé » is not said of a person) |
| closed (an account) | fermé |
| the service it runs under / the model does not answer | le service sous lequel elle fonctionne / Le modèle ne répond pas |
| has to look into it (the operator, when the service's model does not answer) / Research needs it. / This needs it. | … doit s'en occuper. / Une enquête a besoin de ce modèle. / Cette demande a besoin de ce modèle. ("it" said as ce modèle) |
| a library's copy / brought back from its copy / being moved / no longer served here / another process holds its folder | copie / restaurée à partir de sa copie / en cours de déplacement / n'est plus disponible ici / un autre processus occupe son dossier ; a moment ({0}) in brackets after « état : » or « moment : », never « le {0} » |
| a machine of the service / the machine a library runs on / is kept on | machine / la machine sur laquelle fonctionne la bibliothèque « {0} » / qui conserve la bibliothèque « {0} » ; a count of minutes that can be 1 is « {1} min » |
| an account's caps: the month's dollars / fetched pages / jobs at once; spending; a container | budget de {0} / pages récupérées / tâches à la fois ; dépenses ; conteneur. Money (« $5.00 ») and the date (« 2026-12-01 ») stay as the program writes them, the date in brackets: « au début du mois prochain ({1}) », never « le {1} » |
| work the service let start (a run, a chat turn, a translation) / searches this month / the model is busy | tâche / recherches / Le modèle du service est occupé ; the day ({1} = « 1 déc. ») in brackets: « au début du mois prochain ({1}) » |
| storage cap ({0} = « 2.0 GB », as written) / removing something makes room / reading goes on | tout l'espace de stockage autorisé ({0}) / Enlever quelque chose libère de la place / la lecture reste possible |
| own key / the operator's page (Every account) / cap / used / left / no cap / yours (own money) | clé à vous, vos propres clés / Tous les comptes / plafond / utilisé / restant / sans plafond / à vos frais |
| spending plug-in / the heavy helpers' time / pages fetched / a cap's names | plug-in de dépenses / Temps des modèles auxiliaires / pages récupérées / dollars par mois, recherches par mois, pages récupérées par mois, tâches à la fois, minutes par tâche, dollars par tâche, mégaoctets stockés |
| typed in the caps form | off, 5, 5.00, 5,00 and default stay as written: the program reads both the dot and the comma |
| set today / set yesterday / the fold of own keys / a run stopped at its cap | enregistrée aujourd'hui / enregistrée hier / Utiliser vos propres clés (facultatif) / Arrêt au plafond |
| set on {0} (a day, never today or yesterday, which have keys of their own) | enregistrée le {0}: the one place where « le {0} » is right, because {0} here is always a day as « 17 nov. » |
| suspend / delete (an account) / removal day / hand over (a library) / recent activity | suspendre / supprimer / « date prévue : {0} » / confier / Activité récente ; the acts of the log are short noun phrases: connexion, mot de passe changé, bibliothèque confiée… |
| rate limits: "Try again in {0}." / a wait / Wait a moment | Réessayez dans {0}. / {0} seconde – {0} secondes, {0} minute – {0} minutes / Patientez un instant |
| take a library home (the link) / pack (for download) / the archive | Emporter / préparer (pour le téléchargement) / archive |
| a person lets a library go / the operator lets a library go (release to a folder) | laisser partir / libérer |
| kept aside / out of service / take back / take in / hand over / move / days of grace / freeze / suspend, let back | mise de côté / hors service / récupérer / reprendre / confier / déplacer / délai de grâce / geler / suspendre, rétablir |
| the audit log / machine, node / awake, asleep / last word / … ago | journal d'audit / machine, nœud / active, en veille / dernier signe / il y a {0} seconde(s)… as two keys each; a name in a sentence goes in « » after "le compte" (no "de" that would have to elide) |
| Release (the operator's act, was "let go"; a person's own "Let it go" stays laisser partir) / Keep it ⟦…⟧ / For ⟦…⟧ / kept before by {0} | libérer / Garder / Pour le compte / gérée auparavant par {0} |
| invitation / Join ⟦…⟧ / Withdraw ⟦…⟧ / a link not taken yet | invitation / Rejoindre / Retirer / lien pas encore utilisé |

## Fifth round (0.8.1: the places a library avoids, the model server isolated from the network)

| English | French |
|---|---|
| place (a country code the setting names: CN, HK…) | pays ou région (pl. pays et régions) |
| keep away from / avoid; the places this library avoids | éviter; les pays et régions que cette bibliothèque évite |
| a list of places the program fills in ({0} = « China, Hong Kong and Macao ») | after a colon (« ces pays ou régions : {0} »), never after en / au / dans, which would have to agree |
| based or runs in (a provider) | avoir son siège ou fonctionner dans |
| owned by a company based in a place; its owner's place | appartenir à une entreprise ayant son siège dans …; le pays ou la région de son propriétaire |
| states no place / left out / allow / serve (a model) | n'indique ni pays ni région / exclu, exclure / autoriser / proposer (servir, when it is served to the library) |
| provider / router / request | fournisseur / routeur / requête |
| no way out to the network / can reach the network | isolé du réseau / peut accéder au réseau |
| set up with no way out / able to reach the network / the earlier way | installer isolé du réseau / avec accès au réseau / de l'ancienne façon |
| a reason the program fills in | after « Raison : », as a clause in small letters |
| embeddings server / embeddings model | serveur d'embeddings / modèle d'embeddings |
| Docker network / container / proxy / route / address range / start script / log | réseau Docker / conteneur / proxy / route / plage d'adresses / script de démarrage / journal |
| open / closed (values of model_network) | stay as written: « est sur open » |
| an MCP server's answers give addresses | « Les enquêtes peuvent ouvrir les adresses qu'il cite dans ses réponses. » Buttons: Laisser les enquêtes ouvrir les adresses trouvées / N'ouvrir aucune adresse de ses réponses |
| the eight places of the unset list (CN HK MO CU IR KP RU VE), as the language ordinarily names them | Chine, Hong Kong, Macao, Cuba, Iran, Corée du Nord, Russie, Venezuela; a list of them is joined as the program joins every list (Chrome.and: the language's own "and"); any other place by the locale's name for its code |
