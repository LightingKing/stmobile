# Prose preferences: evidence log

The evidence for versions 1.0 to 1.2 (the ten-round blind excerpt test and the
chapter-five trial) was not saved alongside this file. This log starts at
v1.3.

Project names, file paths and story-specific details have been replaced with
general descriptions. "The reader" is the person whose standard
`PROSE_PREFERENCES.md` describes; "the author" is the person who owned the
book being tested (in these runs, the same person).

## v1.3 · 11 October 2026 · roleplay feedback

**Source.** Optimizing a roleplay prompt for a mobile roleplay app, with a
Claude/Codex discussion of the results. The reader marked up one real model
reply: an opening scene with a roleplay character. They bracketed seven
pieces and called the whole thing "so... bleh", adding: "I hate 'he said it
in a tone' the most. so so much."

What they bracketed:
- "and the room tone in here is the fridge" (witty tail clause)
- "you're letting the hallway in" (cute figurative swap)
- "and she'd clearly given up on fixing it" (narrator wink)
- "That's not a set, that's a hostage situation." (spoken not-X-that's-Y)
- "and he said it in a tone" (coy hint; the most hated)
- "Obviously I said yes." (redundant confirmation)
- "just tell me I have to start rehearsing tonight, so I have an excuse to be
  unbearable about it" (self-advertising)

What they left alone: plain direct speech and concrete physical action.

**Reading, agreed with Codex.** Every bracket is a second, cleverer layer added
after a line has made its point, selling the character instead of letting the
reader watch them. Prompt wording that asked for "spark", "humor and play" and
"something that shows who they are" produced it, and model judges scored for
"fun" rewarded it.

**Earlier roleplay evidence** (preset rounds, 3 October 2026): a preset built
on this file's v1 dialogue rules ("restrained dialogue", "no banter") was too
restrained and flat; a popular community preset with a humor push was
"sitcom-y and snippy"; the preferred version was sincere, with full
conversational exchanges. This is the basis for the section 5 and section 10
restraint guard.

**Judges.** In the prompt tournament, judges told to value literary quality
favored flat restraint, and judges told to value fun favored quips. Neither
matched the reader's annotations.

**Weaker models and quoted examples.** Codex argued that quoting the bracketed
lines in a prompt for a smaller model would give it material to imitate. Not
tested.

**Scanner.** The v1.2 scanner flagged none of the seven bracketed pieces. The
v1.3 patterns flag five (all but the tail clause and the figurative swap), with
no hits on 19 liked chapters of an earlier story (about 47,500 words). Two
first-draft patterns ("in a voice", and "not X, it is Y" in general) hit good
lines in those chapters and were narrowed.

**Status.** One sample, roleplay rather than fiction. Not yet checked on
chapter-length prose.

**Model-based scanning, tested 11 October 2026.** A small, fast yes/no
classifier model and Claude Haiku 5.5 were each asked seven questions, one per
v1.3 shape, about 418 sentences: the 19 from the marked-up sample, 150 from
the liked chapters and 249 from roleplay replies. The small model took about
10 seconds and cost under two cents for all of them, and two of its runs
agreed with each other (kappa 0.91). Neither model beat the regex scanner. On
the sample, the small model caught 3 or 4 of the 7 bracketed lines and Haiku
3, against 5 for the regex. Both missed "he said it in a tone". Both also
flagged sincere lines in the liked chapters ("It is.", "All of it.") as
redundant: the small model 31% of chapter sentences and Haiku 14%, against 0%
for the regex. Raising the small model's threshold cut those flags but lost
recall. Neither model helped with the two shapes the regex cannot see. The
scanner stayed regex-only.

**Model-based scanning, optimized 11 October 2026.** A larger test followed:
- **Data.** 1,626 sentences from 24 roleplay replies and 11 models' drafts of
  the same chapter. Claude labelled them; Codex checked them blind, and only
  labels both agreed on counted.
- **Small classifier.** Fourteen prompt variants were tried. The best
  combination reached F1 0.47 on held-out text (precision 0.73, recall 0.35).
- **Haiku 5.5.** Paragraph-level, with the same guide, it reached F1 0.57
  (precision 0.57, recall 0.57).
- **Regex.** It caught nothing on held-out text; its v1.3 patterns only
  generalise to text like the marked-up sample.
- **Codex's check.** Codex judged the two detectors' disagreements blind: 12
  of 25 Haiku-only flags were real, and 0 of 6 classifier-only flags were.
- **Reading.** Haiku is the better semantic detector, but it is still only
  right about half the time on its own extra flags. Use it to propose
  candidates for a human read, not to decide.

**Checking split into two passes (11 October 2026).** From the results above,
the reader and Claude agreed that the script should count and a strong model
should read:
- **The scanner** now measures repeated phrases (n-grams, within and across
  chapters), words used far above the baseline, similes per 1,000 words, the
  share of paragraphs ending on a short sentence, dashes, ellipses, dialogue
  share and and-chains. Each rate is compared with `baseline.json`, built from
  22 author-endorsed chapters across two stories (about 60,000 words). It
  keeps only fixed-wording checks.
- **Removed from the scanner:** narrated absence, negation-reversal,
  not-X-but-Y, hypothetical comparison and contradiction buttons. On chapters
  the author accepted, narrated absence fired 109 times and contradiction
  buttons 69 times, so they were mostly noise. They are now the reviewer's
  job.
- **The reviewer** is a fresh subagent with `REVIEWER.md`. It flags only
  listed shapes, quotes each line, and is told that section 10 lines are off
  limits and zero flags is normal.
- **A first check:** on one model's chapter draft, simile density came out
  3-8x and dashes 3-6x the baseline, matching an earlier ranking's note on its
  "paired metaphor habit".

## v1.4 · 14 October 2026 · a novel revised with two models

**Source.** A fantasy romance fanfic: 54 chapters, about 137,000 words. Codex
wrote the base draft. Claude then added layers in passes the author requested
and approved: liveliness, a "meaner" heroine, dialogue delivery, and
characters "pushing" for what they want. The author read the EPUB chapter by
chapter and gave notes.

**The author's own notes, verbatim where it matters.**
- "The dialogue got too witty again. I like it when dialogue kinda explains
  itself through delivery." A line like "I'm deciding whether I want to stand
  here listening" is "way too moralistic for someone his age". They preferred
  "Y-you don't understand. It's... it's different." A one-chapter workshop
  fixed the level, and was then applied to the whole book. Kept as approved.
- "[The heroine] feels a bit undercooked… she wouldn't do it half measured…
  she avoids asking or pushing in ways I feel she would." The push pass was
  accepted.
- On a Claude rebuild of "Her heart jumped, which was stupid; it was one
  finger." into "It was only one finger, and her heart had jumped anyway.":
  "the replacement is worse than the original, and more... ai-ism".

**Two-model diagnosis.** Claude and Codex each diagnosed both layers
independently. Codex's structural list was the stronger one:
- acknowledgment chains;
- an action's emotional job stated;
- closing scenes into calm;
- re-labelled callbacks;
- tidy settlements;
- briefings.

Claude's list was more sentence-level:
- result-clause gauges;
- the flush catalogue;
- stage beats;
- wry asides;
- deadpan closers.

The two lists were merged into one working note with no quotas.

**Measured against the liked baseline** (rates per 1,000 words; the baseline
is the 60,696 words of liked chapters):

| Habit | Baseline | Book before | Book after |
|---|---|---|---|
| "nodded" | 0 | 0.82 | 0.72 |
| "enough to/that" | 0.27 | 1.0 | 0.57 |
| "I know" opening a line | 0.46 | 0.96 | 0.62 |
| Italic stress | 5.5 | 1.6 | 1.4 |

The receipt-line share (lines that only acknowledge) was close to the
baseline throughout: 6% in the baseline, about 5% in the book. So the "I
know" problem was placement, at emotional moments, rather than overall rate.
Italics were never a problem: Claude's claim that they were a crutch was
wrong, and Codex was right to resist it.

**Web research** (Wikipedia "Signs of AI writing", NousResearch ANTI-SLOP,
EQ-Bench slop-score, AIStoryHub's 758-phrase corpus, Charlie Guo's field
guide):
- The stock-phrase lists were already clean in this book. 0 hits for
  "barely above a whisper", "heart pounding", "something shifted" and the
  like.
- What applied was structural: interpretive trailing participles, triads,
  same-volume scenes, and conflicts resolved within the scene. That last one
  matches an anecdotal report about Claude in long fiction.
- Every serious source stresses that the signal is clusters, not single
  phrases.

**Process findings.**
- A reviewer applying a general rule ("upset speech can be fluent") removed
  delivery the author had approved. Explicit approvals now outrank the
  agreement.
- The fix pass created new tics: about six deadpan closers, cappers, and the
  "…anyway" overturn rebuild. The fixes need their own review.

**Scanner.** It gains a habits line, reported per chapter because per-section
rates were too noisy. It counts:
- result-clause gauges;
- "nodded" and "shrugged";
- italics, shown for information only.

It also gains two narrow hints: interpretive participles, and the "…anyway."
overturn. The baseline was rebuilt from the same sources and the same 60,696
words, with the new rates added.

Two candidate counters were dropped after testing on the liked chapters:
- "looked at" and "glanced", which liked chapters also use;
- the receipt-line share, which liked chapters had at 6–14%, against 2–10% in
  the draft.

On four liked chapters the final habits line raises no "!".

**Status.** One novel, one author. The rates are comparable to the baseline;
the shape judgments are not yet tested on another book.

## v1.5 · 15 October 2026 · the shorthand moment

**Source.** The author, reading the same novel in EPUB, flagged one exchange
in chapter 24. A friend asks whether the girl even likes the hero; he answers
"She came back up the stairs." The friend asks "What stairs?" and gets
"Never mind." The author's words: "a particular ai ism that's a bit hard to
describe but it basically creates a moment that's used as shorthand for
meaning."

**Fix the author proposed, and the one used.** If the character must think
of the moment, give him a brief flashback (her footsteps going away down the
stairs, then coming back faster, her pink in the doorway) and let him say
something less knowing: "She's... well, she's nice sometimes."

**Why it's distinct from callbacks, which the author loves.** A good callback
comes back in use and nobody points at it. The shorthand moment has a
character point at it, as the whole of an answer, and the cryptic follow-up
("What stairs?" / "Never mind.") makes the reference a little performance for
the reader. It sits between "labelled callbacks" and the coy hint: the
meaning is withheld behind a token instead of being said or shown.

**Book sweep.** Searched the whole novel afterwards: by grep for "What X?" /
"Never mind" / short past-event answers, and by a Codex read of all 54
chapters with the definition above.

## v1.5 · 15 October 2026 · the author's own hand edit

**Source.** The author rewrote the opening sections of chapter 24 of the same
novel by hand, "so you can kinda get a better feel for my preferred human
style of prose". About 25 changes. Almost all of them loosened the text; none
tightened it.

**What the word diff shows.**
- Speech gained padding and afterthoughts: "Really. You'd better.", "I'm
  assuming you wouldn't want that.", "I suppose.", "Well." at the front of
  lines, "really" inside them.
- Self-correction: "I told Sam I wanted to ask you." became "I told Sam.
  Maybe. Well, I told him I asked you."
- Fluster as rambling: the hero's acceptance "All right." became "O-okay.
  Noted. Yep. Noted it."; his question became "I... well, I was hoping. You
  would go. With me.", preceded by a new "So..." / "So?" false start.
- Narration gained colloquial afterthoughts: "It wasn't going very well.",
  "An awful hassle, really.", "He might have too.", "Well, his face did a
  funny thing, but she didn't put much mind in it. He dropped it."
- Plainer words and contractions: deciding → thinking, did not → didn't,
  laughed despite himself → laughed a bit, left it alone → decided to not.
- A free indirect question: "It was chewing a lace." became "Was it chewing
  a lace?"
- A precise callback was cut: "the girl he had decided on before
  Chemistry" became "the girl he had decided on."
- Wit was softened: "I am saying no." became "Well, no."; "She'd get your
  slipper sorted." became "Well. At least she'd get your slipper sorted."

**Reading.** The model's prose was already clean of stock phrases and
banned shapes. What remained was over-construction: every line economical
and purposeful. The author's version tolerates redundancy, slight
awkwardness and words that do nothing but sound like a person. That matches
the earlier finding that clipped dialogue reads as flat (section 10), and
extends it to narration.


## v1.6 · 15 October 2026 · calibration against human fanfiction

**Source.** After the v1.5 review of a 92,600-word model-written novel in a
cartoon fandom, the reader asked for a comparison with real fanfiction "so
you aren't assuming what it looks like", and then for the method to go into
this skill: "real human writing can be learned from but it has to be done
constantly because it's easy to miss some of the biggest differences."

**Corpus.** Four popular, complete works on AO3 in the same fandom, from the
fandom tag sorted by kudos: four authors, published 2015–2020, 23k to 95k
words each, about 222,000 words in all. A comparison in a second fandom was
attempted but AO3 returned 525 errors for the whole session.

**Measured** (per 1,000 words, human min–max vs. draft):
- questions in narration 0.68–2.65 vs 0.02
- ellipses 2.08–4.81 vs 0.05
- "!" 2.01–9.06 vs 0.78
- "?" 6.23–12.43 vs 3.43
- "said Name" inversion 0–0.46 vs 4.89
- "very" 0.64–1.29 vs 4.49
- "the way" 0.29–0.59 vs 3.18
- "And"-initial sentences 1.23–2.48 vs 4.14

**No match in 222k human words:** "came out of" (36 in the draft), "she felt
it" (45), "the way she'd" (31), "very quietly" (26), "and she didn't" (27).
"came down" was 67 against 1.

**Within the human range:** narration sentence length, paragraph length,
italics, and em dashes (humans ranged from 0 to 8.4, so dashes don't
separate anything).

**Reading.** The draft had passed the scanner, which uses liked *model*
chapters as its baseline, and ten reviewers. The scanner couldn't see a
register that the liked chapters share. Close reading found the main
difference was not phrasing but stance: human close-third narration is the
character thinking (questions, doubt, "Maybe…" strings, trailing off,
distraction, being wrong), and the model narration observed. Human peaks go
inward, into italic thoughts and repetition, where the model stayed on the
body. The humans also had habits the reader's standard bans (said+adverb up
to 3.9/1k, said-bookisms up to 3.2/1k, epithets up to 1.1/1k); those are
marked "don't import".

**A slip in the demo.** While rewriting a sample scene in the human
direction, the model added a comic-button afterthought ("Politely. Which
was honestly worse."), the v1.5 failure mode, and cut it on review.
Loosening toward human voice still needs the section 9 checks.

**The full rewrite (same day).** The whole novel was then rewritten in the
human direction. Afterwards it was inside the human range on every feature
except ellipses, "the way" and "And"-initial sentences, which were then
thinned. Six fresh reviewers, five chapters each, found 77 flags. Most of
them were new habits created by the rewrite itself, not old ones:
- the narrator commenting on delivery after the line: "She said it like
  she'd said it a lot of times", "said *yours* with a twist on it"
  (six of these in five chapters);
- "X looked at her for a long time" as the pause before every weighty
  line (about 40);
- "she found out she couldn't" for her own reaction (five);
- the same "…even…?" form for every narration question, and questions
  answered or echoed in the very next words ("Should she say something
  else? She should say something else.");
- tag-question paragraph closers ("Hadn't she?", "wasn't it?");
- a character's tell repeated in the same words each time ("her outline
  went wobbly all over", "colors went over her, red and green and gold");
- "from the middle out" for every color change (seven in three chapters);
- new comic buttons ("[a character] would've had a diagram", "Not even
  about [a side character]").

**Mechanical passes break things.** A regex that flipped "said Name" to
"Name said" turned "said Mr. Okafor" into "Mr said. Okafor." The first
"And"-removal pass also stripped "And" from inside dialogue, because the
check looked for curly quotes and the files used straight ones. Check the
quote style first, exclude honorifics, keep a backup, and have a diff-only
reviewer read the result.

**AO3 availability.** The second-fandom fetch failed all session on
Cloudflare 525 errors. The tool gave up cleanly; try again later.

## v1.6 addendum · 16 October 2026 · a second opinion from another model family

**Source.** The reader asked for a targeted pass by Gemini 4 Argon for a
different point of view. The whole rewritten novel and this file went to it
through an OpenAI-compatible API. It cost about $0.32 for 132k tokens in and
6k out.

**What it saw that six Claude reviewers didn't put first.** These were
checked against the text:
- *Motif metadata recited.* A recurring detail comes back with its full
  original clause every time ("the gold ring she never took off even to do
  the bleach loads", 3 times; "cut them over the kitchen sink with a ruler",
  3 times). The first mention carries the detail; after that, the bare
  noun.
- *The same closing beat.* 11 of 30 chapters ended on counting, the pulse
  or the dryer. A strong motif at the end of every chapter turns into a
  subroutine.
- *Ensemble by props.* Secondary characters each perform one assigned tic
  whenever they appear (knuckles, colors, wobbling edges), and scenes
  with five people stay one-on-one conversations. Human fic lets two side
  characters talk over each other.
- *No hangout or present-day texture.* The book has no phones, nothing
  brand-specific, no low-stakes chapter, and the heroine is never petty.
  Human fandom writing is full of these.
- *Question clusters bolted onto calm paragraphs.* Two or three short
  questions tacked onto an observational paragraph, when the doubt should
  be woven into the observation itself.

**Where it was wrong.** It inflated counts (it said "for a long time"
appeared 65+ times; there were 32). It criticized the climax's
power-inventory, which the author asked for. Its sample rewrites
included quips ("cold ring and all"). Use a second model to find
patterns, not to supply lines, and check every count it gives.

## v1.7 · 21 October 2026 · measuring a romance against its own gold chapters

**Source.** The same fantasy romance fanfic, now about 186,000 words after
a rewrite. The author named chapters 1–25 as the gold standard and asked
for 26–54 to be brought up to it. The later chapters included a large plot
rewrite (a character spared, a duel, a split war chapter) written by the
model in the same session.

**Measurements** (gold mean, with the drift in the model's new chapters).
Mean words per sentence 7.9 (new war chapters 10.6–10.8). Sentences of
5 words or fewer 46% (37–39%). Words per paragraph 15.3 (24–30). Sentences
with two or more "and"s 2.2% (9–13%). "As if/as though" 0.41 per 1,000
words (1.2–2.8). Dialogue share 26% (14–21% in action chapters). The older
model chapters (45–54) had drifted less but the same way. Chapters 26–30
were within the gold range.

**Fix.** Splitting chains, cutting "as if", and breaking paragraphs at beat
changes brought the worst chapters to 9.1–9.8 words a sentence and 3–5%
chains. The deliberate run-ons were left long: a flashback memory, a
breakdown speech, the break of a duel. A Gemini reviewer pass on the
revised chapters found four lines and no new chains. Things the fix pass
itself introduced, and that had to be caught: one "…anyway" overturn; one
pronoun stranded after its antecedent sentence was cut; and earlier,
several "for a long moment" and "nodded" beats.

**The contrast cut.** The author asked for a flashback that feels like a
famous anime flashback cut: a warm, glowing memory, hard-cut to the same
face covered in blood. They sent stills and then their own rough sketch.
The sketch beat the model's first draft on four choices: the memory as a
scene with the child talking to the butterfly; the butterfly landing on the
face, mirroring the blood; the present-day character speaking flatly ("It's
done. It's over."); and a cut visible on the page. The author said the
revised scene "really hits". Afterwards they allowed it to be tightened "as
long as the vibe remains". The memory and the breakdown stayed long. The
narration round them was cut to gold pace.

**Composure as anger.** The author asked for a character whose anger,
learned from her mother's composure, is "less visible but still very real
under the surface", and who for once gets properly mad. This became
section 4's composure note and section 7's "the break should sound
different" point.

## v1.8 · 22 October 2026 · first drafts judged blind against gold

**Source.** The author asked for the skill to produce prose at the level of
the book's best gold chapter on a first pass, "or at the very least no
manual passes by the user", with Gemini 4 Argon as a second opinion.

**Method.** Fresh writer subagents got the same brief (no plot prose from
the target chapter) and a skill path, and ran the skill's whole workflow
unaided. `gold_judge.py` gave the judge PROSE_PREFERENCES and two or three
shuffled, unlabelled texts: a 2,400-word gold excerpt, the draft(s), and
sometimes the existing hand-steered version of the same section.

**Calibration.** Gold 9, an existing hand-steered section 6.5, an
unrevised late chapter 2.5. The scanner and simple counts (narration
questions, summarised speech, dialogue share, "as if") barely separated the
unrevised chapter from gold.

**Results.**
- Test 1 (one section of chapter 26, v1.7 skill): the first draft scored
  9.5 against gold 8, and 9.5 against gold 5.5 in a pairwise rerun. "No
  manual pass needed."
- Test 2 (a section of chapter 28): v1.7 draft 9 (gold 7); in a three-way
  run v1.7 draft 9, v1.8 draft 6.5, gold 4.5. The v1.8 writer skipped the
  judge step. Judged alone against gold, that same unfixed draft scored 9.5
  (gold 6), so the 6.5 was mostly the company it kept. After the full v1.8
  pipeline (two judge rounds, a reviewer pass, about 22 lines changed,
  including phrases lifted from the gold chapter and the preference doc), a
  final three-way run gave v1.8 9.5, v1.7 7, gold 5.5.
- The judge also called the gold chapter's voiced asides (", which was
  silly") mechanical. They are the author's approved voice; the skill now
  warns never to cut a draft below gold's own habits on the judge's word.

**What the numbers mean.** The gold excerpt scored 9, 8, 5.5, 7 and 4.5 in
five runs. The judge's scores are relative and favour this document's
checklist, especially narration questions, which the gold chapter has few
of. It cannot certify parity with gold; it can rank drafts and quote real
slips.

**What still slipped into first drafts** (now PROSE_PREFERENCES section 12,
"First-draft failures"): fused quotations from the writer's own edits,
delivery glosses, an aphorism credited to a character, doubled emphasis
and spoken echo-denial, clever detection, too many quotable scene endings,
"came out of" clusters, the book's own fix-tics ("Somebody had to"), side
characters who serve, summarised turns, and phrases borrowed from gold,
from the book sheet and from the preference doc's examples.

**What the book sheet fixed.** The v1.7 writers never saw the author's
character steering, which lived in the orchestrating agent's private memory
files: the heroine's sweet side at full strength when the hero is hurt, the
hero partisan rather than her judge, his best friend never background noise.
The v1.8 writer, with a `STYLE-SHEET.md`, had the hero lie to his friend for
her, the heroine's eyes going wet over his hand, and her anger shown as
overcontrol. It also copied the sheet's example line, so the sheet now holds
instructions only.

**Changes.** SKILL.md "First pass at gold standard" protocol (book sheet,
ear-tuning read, scene plan, first-draft failures read, scanner including
a cross-file pass against the gold chapter, mandatory blind gold judge
loop, reviewer); `scripts/gold_judge.py`; the first-draft failures list;
a book sheet for the project and a pointer to it from the project's agent
instructions.
