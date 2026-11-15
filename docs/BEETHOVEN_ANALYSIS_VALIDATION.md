# Beethoven analysis validation

On November 11, 2026, the Beethoven folder and its subfolders contained 22 supported recordings, comprising 21 MP3s and one FLAC. Version 2 decoded all 22, produced no tempo estimates, and named a key for 11. The saved catalog also contained an unavailable entry for an earlier location of Minuet in G.

Version 3 decoded all 22, reported a pulse for all 22, and named an audio key for 15. Three of the remaining recordings have competing key estimates. Four have inconclusive tonal evidence. Median elapsed analysis per recording rose from 0.66 to 1.67 seconds in the direct analyzer runs.

The portable Windows build imported all 22 files into an isolated SQLite catalog with current analysis and a pulse estimate for every entry. On reopening the catalog, it refreshed a seeded version 2 result with no tempo and preserved manual energy and movement ratings.

## Independent tempo comparison

The reference used FFmpeg to decode the same local audio to mono at 11,025 Hz and librosa 0.11.0 to estimate tempo from spectral flux. It took the median of estimates from five 36-second clips across long files and three clips across the two shorter files. Sangitam measures the shorter recordings as one continuous passage. Neither test sent audio over the network. [librosa onset strength](https://librosa.org/doc/0.11.0/generated/librosa.onset.onset_strength.html) · [librosa tempo estimation](https://librosa.org/doc/0.11.0/generated/librosa.feature.tempo.html)

The representative pulses agreed within 8% on 15 of 22 recordings. On all 22, the reference median fell inside the measured local range or within 8% of its nearest edge. Wide ranges indicate that the recordings can support several pulse rates or beat levels. This comparison measures agreement between estimators. Performance BPM would require beat annotations for the specific recording. [Tempo comparison tolerance](https://mir-eval.readthedocs.io/stable/api/tempo.html)

| Recording | Sangitam pulse and range (BPM) | Reference median (BPM) | Audio key or candidates |
| --- | ---: | ---: | --- |
| Piano Concerto No. 1, Phase One | 159 (83–170) | 117 | C major |
| Piano Concerto No. 1, Phase Two | 106 (100–114) | 110 | E-flat major |
| Piano Concerto No. 2 | 110 (105–114) | 110 | B-flat major |
| Piano Concerto No. 4, Phase One | 96 (68–132) | 123 | E minor |
| Piano Concerto No. 4, Phase Two | 135 (86–142) | 136 | G major |
| Emperor Concerto, Phase One | 125 (115–130) | 126 | E-flat major |
| Emperor Concerto, Phase Three | 87 (82–124) | 129 | E-flat major |
| Emperor Concerto, Phase Two | 164 (91–168) | 161 | B major |
| Für Elise | 125 (121–127) | 123 | A minor or E minor |
| Minuet in G | 87 (82–111) | 112 | G major |
| Hammerklavier, Uchida, I. Allegro (FLAC) | 106 (99–116) | 117 | B-flat major |
| Moonlight | 159 (127–160) | 161 | C-sharp minor |
| Pastoral | 97 (87–136) | 140 | D major |
| The Tempest | 129 (88–133) | 129 | D minor |
| Waldstein | 149 (84–157) | 152 | Inconclusive |
| Appassionata | 115 (107–121) | 117 | Inconclusive |
| Les Adieux | 123 (119–126) | 123 | E-flat major or B-flat major |
| Hammerklavier, Eschenbach, II. Scherzo (MP3) | 109 (72–166) | 105 | Inconclusive |
| Pathétique, Phase One | 137 (102–150) | 136 | C minor or E-flat major |
| Pathétique, Phase Three | 107 (72–111) | 110 | C minor |
| Pathétique, Phase Two | 111 (102–164) | 112 | A-flat major |
| Ghost Trio | 78 (69–122) | 120 | Inconclusive |

## Online recording and score checks

- Uchida’s FLAC matches the artist, first movement and 11:24 duration on the Decca release. B-flat major remains the audio estimate. [Decca listing](https://classical.music.apple.com/us/album/1452527081)
- The three Pathétique file durations match the Kovacevich track listing within a few seconds. The second movement is in A-flat major, which the revised analysis recovered. The first movement still has competing audio estimates. [Warner track listing](https://classical.music.apple.com/us/album/1479066174) · [Pathétique movement keys](https://dfp.informance.biz/content/pages/134-sonata-pathtique-in-c-minor/577-introduction)
- Henle gives A minor for Für Elise. Version 2 selected E minor. Version 3 gives A minor and E minor as competing estimates, so the evidence remains insufficient to resolve the key from the sampled audio alone. [Henle Urtext](https://www.henle.de/Bagatelle-in-a-minor-WoO-59-Fuer-Elise/HN-1347)
- The Warner listing identifies Waldstein and Appassionata as excerpts. Their published sonata keys are C major and F minor. Sangitam leaves their audio estimates unresolved. The existing musical-key field in Classical / credits can store a verified key independently of the analysis. [Warner track listing](https://classical.music.apple.com/us/album/1479066174)
