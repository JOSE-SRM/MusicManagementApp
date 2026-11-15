package sangitam.desktop.library;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata.Field;

import java.text.Normalizer;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Derives composition fields without copying performance details from another recording. */
public final class WorkDetails {
    private static final Pattern OPUS = Pattern.compile("\\b(?:op(?:us)?\\.?|woo|bwv|kv?\\.?|rv|d\\.)\\s*\\d+[a-z]?(?:\\s*,?\\s*(?:no\\.?|number)\\s*\\d+)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY = Pattern.compile("\\b[A-G](?:[#♯b♭]|[- ](?:flat|sharp))?\\s+(?:major|minor)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONCERTO = Pattern.compile("\\bpiano (?:concerto?|conerto) (?:no |number )?([1-5])\\b");
    private static final Pattern BALLADE = Pattern.compile("\\bballade.*\\bop (23|38|47|52)\\b");
    private static final Pattern BRANDENBURG = Pattern.compile("\\bbwv\\s*(104[6-9]|105[01])\\b");
    private static final Pattern BACH_SUITE = Pattern.compile("\\bbwv\\s*(106[6-9])\\b");
    private static final Pattern HUNGARIAN_DANCE = Pattern.compile("\\bhungarian dance (?:no |number )?(\\d{1,2})\\b");
    private static final Pattern SEASON = Pattern.compile("\\b(?:the four seasons|le quattro stagioni)\\s+(spring|summer|autumn|fall|winter)\\b");
    private static final String[] CONCERTO_OPUS = {"15", "19", "37", "58", "73"};
    private static final String[] CONCERTO_KEYS = {"C major", "B-flat major", "C minor", "G major", "E-flat major"};
    private static final Map<String,String> PERIODS = Map.ofEntries(
        Map.entry("beethoven", "Classical–Romantic transition"), Map.entry("mozart", "Classical"),
        Map.entry("haydn", "Classical"), Map.entry("boccherini", "Classical"), Map.entry("bach", "Baroque"),
        Map.entry("handel", "Baroque"), Map.entry("vivaldi", "Baroque"), Map.entry("pachelbel", "Baroque"),
        Map.entry("chopin", "Romantic"), Map.entry("liszt", "Romantic"), Map.entry("schumann", "Romantic"),
        Map.entry("brahms", "Romantic"), Map.entry("schubert", "Romantic"), Map.entry("tchaikovsky", "Romantic"),
        Map.entry("mascagni", "Romantic"), Map.entry("bizet", "Romantic"), Map.entry("mendelssohn", "Romantic"),
        Map.entry("dvorak", "Romantic"), Map.entry("rimsky korsakov", "Romantic"), Map.entry("rossini", "Romantic"),
        Map.entry("grieg", "Romantic"), Map.entry("mussorgsky", "Romantic"), Map.entry("delibes", "Romantic"),
        Map.entry("borodin", "Romantic"), Map.entry("bruch", "Romantic"), Map.entry("puccini", "Romantic"),
        Map.entry("dukas", "Romantic"), Map.entry("verdi", "Romantic"), Map.entry("glinka", "Romantic"),
        Map.entry("wagner", "Romantic"), Map.entry("offenbach", "Romantic"), Map.entry("sibelius", "Romantic"),
        Map.entry("smetana", "Romantic"), Map.entry("johann strauss ii", "Romantic"),
        Map.entry("rachmaninoff", "Romantic"), Map.entry("debussy", "Early modern"), Map.entry("ravel", "Early modern"),
        Map.entry("orff", "20th century"), Map.entry("barber", "20th century"),
        Map.entry("prokofiev", "20th century"), Map.entry("khachaturian", "20th century"));
    private static final Map<String,String> COMMON_COMPOSERS = Map.ofEntries(
        Map.entry("beethoven", "Ludwig van Beethoven"), Map.entry("mozart", "Wolfgang Amadeus Mozart"),
        Map.entry("chopin", "Frédéric Chopin"), Map.entry("haydn", "Joseph Haydn"),
        Map.entry("vivaldi", "Antonio Vivaldi"), Map.entry("handel", "George Frideric Handel"),
        Map.entry("brahms", "Johannes Brahms"), Map.entry("liszt", "Franz Liszt"),
        Map.entry("tchaikovsky", "Pyotr Ilyich Tchaikovsky"), Map.entry("debussy", "Claude Debussy"),
        Map.entry("ravel", "Maurice Ravel"), Map.entry("rachmaninoff", "Sergei Rachmaninoff"),
        Map.entry("schubert", "Franz Schubert"), Map.entry("pachelbel", "Johann Pachelbel"),
        Map.entry("mascagni", "Pietro Mascagni"), Map.entry("orff", "Carl Orff"), Map.entry("barber", "Samuel Barber"),
        Map.entry("bizet", "Georges Bizet"), Map.entry("mendelssohn", "Felix Mendelssohn"),
        Map.entry("dvorak", "Antonín Dvořák"), Map.entry("rimsky korsakov", "Nikolai Rimsky-Korsakov"),
        Map.entry("rossini", "Gioachino Rossini"), Map.entry("sibelius", "Jean Sibelius"),
        Map.entry("schumann", "Robert Schumann"), Map.entry("grieg", "Edvard Grieg"),
        Map.entry("greig", "Edvard Grieg"), Map.entry("mussorgsky", "Modest Mussorgsky"),
        Map.entry("delibes", "Léo Delibes"), Map.entry("borodin", "Alexander Borodin"),
        Map.entry("bruch", "Max Bruch"), Map.entry("puccini", "Giacomo Puccini"),
        Map.entry("prokofiev", "Sergei Prokofiev"), Map.entry("dukas", "Paul Dukas"),
        Map.entry("boccherini", "Luigi Boccherini"), Map.entry("verdi", "Giuseppe Verdi"),
        Map.entry("glinka", "Mikhail Glinka"), Map.entry("khachaturian", "Aram Khachaturian"),
        Map.entry("wagner", "Richard Wagner"), Map.entry("offenbach", "Jacques Offenbach"),
        Map.entry("smetana", "Bedřich Smetana"), Map.entry("johann strauss ii", "Johann Strauss II"));
    private static final Map<String, String> SORTED_NAMES = COMMON_COMPOSERS.values().stream().distinct()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(name -> sortedWords(fold(name)), name -> name));

    private WorkDetails() { }

    static boolean genericWorkLabel(String label, String form) {
        String value = fold(label);
        String type = fold(form);
        return !type.isBlank() && (value.equals(type) || value.equals(type + "s")
            || type.equals("concerto") && value.matches("brandenburg concertos?( complete)?")
            || type.equals("suite") && value.matches("orchestral suites?( complete)?"));
    }

    static String composerHint(Track track) {
        String context = fold(track.title() + " " + track.album() + " " + track.path());
        if (context.matches(".*\\bbwv\\s*\\d+.*") || context.contains("johann sebastian bach"))
            return "Johann Sebastian Bach";
        String titleCredit = uniqueComposer(fold(track.title() + " " + track.path().getFileName()));
        if (!titleCredit.isBlank()) return titleCredit;
        String credited = canonicalComposer(track.artist());
        if (!credited.equals(track.artist()) || COMMON_COMPOSERS.containsValue(credited) || credited.equals("Johann Sebastian Bach")) return credited;
        return uniqueComposer(context);
    }

    private static String uniqueComposer(String text) {
        var matches = COMMON_COMPOSERS.entrySet().stream()
            .filter(entry -> (" " + text + " ").contains(" " + entry.getKey() + " "))
            .map(Map.Entry::getValue).distinct().toList();
        return matches.size() == 1 ? matches.getFirst() : "";
    }

    static String canonicalComposer(String value) {
        String text = fold(value);
        if (java.util.Set.of("beethoven ludvig von", "beethoven ludwig von", "ludvig von beethoven",
            "beethoven ludwig van", "ludwig von beethoven").contains(text)) return "Ludwig van Beethoven";
        if (java.util.Set.of("bach johann sebastian", "johann sebastian bach", "j s bach").contains(text))
            return "Johann Sebastian Bach";
        var exact = COMMON_COMPOSERS.get(text);
        if (exact != null) return exact;
        return SORTED_NAMES.getOrDefault(sortedWords(text), value);
    }

    private static String sortedWords(String value) {
        return java.util.Arrays.stream(value.split(" ")).sorted().collect(java.util.stream.Collectors.joining(" "));
    }

    public static Map<Field,String> fromTitle(String title, String composer, boolean movement) {
        var fields = new EnumMap<Field,String>(Field.class);
        if (!title.isBlank()) fields.put(Field.WORK, title);
        if (!composer.isBlank()) fields.put(Field.COMPOSER, composer);
        var opus = OPUS.matcher(title);
        if (opus.find()) fields.put(Field.OPUS, opus.group());
        var key = KEY.matcher(title);
        if (key.find()) fields.put(movement ? Field.KEY : Field.WORK_KEY, key.group());
        String period = periodFor(composer);
        if (!period.isBlank()) fields.put(Field.PERIOD, period);
        return fields;
    }

    static String periodFor(String composer) {
        String name = " " + fold(canonicalComposer(composer)) + " ";
        var matches = PERIODS.entrySet().stream().filter(entry -> name.contains(" " + entry.getKey() + " ")
            && (!entry.getKey().equals("bach") || name.equals(" johann sebastian bach "))).map(Map.Entry::getValue).distinct().toList();
        return matches.size() == 1 ? matches.getFirst() : "";
    }

    public static Map<Field,String> reference(Track track) {
        String composer = track.metadata().text(Field.COMPOSER);
        String identity = fold(track.title() + " " + track.path().getFileName() + " " + track.metadata().text(Field.WORK)
            + " " + track.metadata().text(Field.OPUS));
        String context = fold(composer + " " + track.artist() + " " + track.album() + " " + track.path().getParent());
        var savedComposer = track.metadata().fields().get(Field.COMPOSER);
        boolean editableComposer = savedComposer == null || savedComposer.origin() == sangitam.desktop.model.TrackMetadata.Origin.FOLDER_HINT
            || savedComposer.origin() == sangitam.desktop.model.TrackMetadata.Origin.TITLE_HINT;
        String creditedComposer = canonicalComposer(editableComposer && composer.isBlank() ? composerHint(track) : composer);
        var namedWork = namedWork(creditedComposer, identity);
        if (!namedWork.isEmpty()) return namedWork;
        if ((editableComposer || fold(composer).contains("smetana")) && context.contains("smetana")
            && (identity.contains("moldau") || identity.contains("vltava"))) {
            return documented("Má vlast", "Bedřich Smetana", "Symphonic Poem", "", "", "");
        }
        if ((editableComposer || fold(composer).contains("strauss")) && context.contains("strauss")) {
            if (identity.contains("die fledermaus"))
                return documented("Die Fledermaus", "Johann Strauss II", "Operetta", "", "", "");
            if (identity.contains("persian march") && identity.matches(".*\\bop 289\\b.*"))
                return documented("Persian March, Op. 289", "Johann Strauss II", "March", "Op. 289", "", "");
        }
        var brandenburg = BRANDENBURG.matcher(identity);
        if ((editableComposer || fold(composer).contains("bach")) && identity.contains("brandenburg") && brandenburg.find()) {
            int opus = Integer.parseInt(brandenburg.group(1));
            return documented("Brandenburg Concerto No. " + (opus - 1045) + ", BWV " + opus, "Johann Sebastian Bach",
                "Concerto", "BWV " + opus, "", "");
        }
        var suite = BACH_SUITE.matcher(identity);
        if ((editableComposer || fold(composer).contains("bach")) && identity.contains("suite") && suite.find()) {
            int opus = Integer.parseInt(suite.group(1));
            return documented("Orchestral Suite No. " + (opus - 1065) + ", BWV " + opus, "Johann Sebastian Bach",
                "Suite", "BWV " + opus, "", "");
        }
        if ((editableComposer || fold(composer).contains("mozart")) && identity.matches(".*\\bkv?\\s*622\\b.*")
            && (identity.contains("concerto") || context.contains("mozart"))) {
            var fields = documented("Clarinet Concerto in A major, K. 622", "Wolfgang Amadeus Mozart",
                "Concerto", "K. 622", "Clarinet", "A major");
            if (fold(track.title()).endsWith(" adagio")) fields.put(Field.MOVEMENT, "2. Adagio");
            return fields;
        }
        if ((editableComposer || fold(composer).contains("tchaikovsky")) && identity.contains("swan lake")
            && (context.contains("tchaikovsky") || identity.matches(".*\\bop 20\\b.*"))) {
            return documented("Swan Lake, Op. 20", "Pyotr Ilyich Tchaikovsky", "Ballet", "Op. 20", "", "");
        }
        var dance = HUNGARIAN_DANCE.matcher(identity);
        if ((editableComposer || fold(composer).contains("brahms")) && context.contains("brahms") && dance.find()) {
            int number = Integer.parseInt(dance.group(1));
            if (number >= 1 && number <= 21)
                return documented("Hungarian Dance No. " + number, "Johannes Brahms", "Dance", "WoO 1", "", "");
        }
        if ((editableComposer || fold(composer).contains("beethoven"))
            && (context.contains("beethoven") || identity.contains("piano trio") && identity.contains("archduke"))) {
            if (identity.contains("pathetique") || identity.matches(".*\\bop 13\\b.*"))
                return beethoven("Piano Sonata No. 8 (Pathétique)", "Op. 13", "C minor", "1797–1799", false);
            if (identity.contains("appassionata") || identity.contains("appasionata") || identity.matches(".*\\bop 57\\b.*"))
                return beethoven("Piano Sonata No. 23 (Appassionata)", "Op. 57", "F minor", "1804–1806", false);
            if (identity.contains("waldstein") || identity.matches(".*\\bop 53\\b.*"))
                return beethoven("Piano Sonata No. 21 (Waldstein)", "Op. 53", "C major", "1803–1804", false);
            if (identity.contains("hammerklavier") || identity.matches(".*\\bop 106\\b.*"))
                return beethoven("Piano Sonata No. 29 (Hammerklavier)", "Op. 106", "B-flat major", "1817–1818", false);
            if (identity.contains("fur elise") || identity.matches(".*\\bwoo 59\\b.*"))
                return beethoven("Für Elise", "WoO 59", "A minor", "1810", true);
            if (identity.contains("piano trio") && (identity.contains("archduke") || identity.matches(".*\\bop 97\\b.*"))) {
                var trio = beethoven("Piano Trio No. 7 (Archduke)", "Op. 97", "B-flat major", "1810–1811", false);
                trio.put(Field.INSTRUMENTATION, "Piano, violin, cello");
                return trio;
            }
            var number = CONCERTO.matcher(identity);
            if (number.find()) {
                int position = Integer.parseInt(number.group(1));
                String opus = "Op. " + CONCERTO_OPUS[position - 1];
                String title = "Piano Concerto No. " + position + ", " + opus + (position == 5 ? " (Emperor)" : "");
                var found = OPUS.matcher(track.title());
                if (!found.find() || fold(found.group()).equals(fold(opus)))
                    return beethoven(title, opus, CONCERTO_KEYS[position - 1], "", false);
            }
        }
        var ballade = BALLADE.matcher(identity);
        if ((editableComposer || fold(composer).contains("chopin")) && context.contains("chopin") && ballade.find()) {
            String number = ballade.group(1);
            int position = java.util.List.of("23", "38", "47", "52").indexOf(number) + 1;
            String opus = "Op. " + number;
            String key = java.util.List.of("G minor", "F major", "A-flat major", "F minor").get(position - 1);
            var fields = fromTitle("Ballade No. " + position + ", " + opus, "Frédéric Chopin", false);
            fields.put(Field.FORM, "Ballade");
            fields.put(Field.INSTRUMENTATION, "Piano");
            fields.put(Field.WORK_KEY, key);
            fields.put(Field.KEY, key);
            return fields;
        }
        var fields = fromTitle(track.metadata().text(Field.WORK), composer, false);
        fields.remove(Field.WORK);
        return fields;
    }

    static String referenceSource(Map<Field, String> fields) {
        return switch (fields.getOrDefault(Field.COMPOSER, "")) {
            case "Frédéric Chopin" -> "Fryderyk Chopin Institute reference catalogue";
            case "Johann Sebastian Bach" -> "Bach-Archiv Leipzig reference catalogue";
            case "Wolfgang Amadeus Mozart" -> "Mozarteum Köchel catalogue";
            case "Pyotr Ilyich Tchaikovsky" -> "Tchaikovsky Research work catalogue";
            case "Johannes Brahms" -> "Brahms-Institut work reference";
            case "Bedřich Smetana" -> "Czech National Museum work catalogue";
            case "Johann Strauss II" -> "Wienbibliothek score catalogue";
            case "Antonio Vivaldi" -> "Bärenreiter score catalogue";
            case "Sergei Rachmaninoff" -> "Boosey & Hawkes score catalogue";
            case "Paul Dukas", "Carl Orff" -> "Schott Music score catalogue";
            case "Jean Sibelius" -> "Breitkopf & Härtel score catalogue";
            case "Nikolai Rimsky-Korsakov" -> "Published score reference";
            default -> "Beethoven-Haus Bonn reference catalogue";
        };
    }

    private static Map<Field, String> namedWork(String composer, String identity) {
        return switch (composer) {
            case "Wolfgang Amadeus Mozart" -> identity.contains("eine kleine nachtmusik") && identity.matches(".*\\bkv?\\s*525\\b.*")
                ? documented("Eine kleine Nachtmusik, K. 525", composer, "Serenade", "K. 525", "", "G major") : Map.of();
            case "Antonio Vivaldi" -> fourSeasons(composer, identity);
            case "Sergei Rachmaninoff" -> identity.matches(".*\\bpiano (?:concerto?|conerto) (?:no |number )?2\\b.*\\bop 18\\b.*")
                ? documented("Piano Concerto No. 2 in C minor, Op. 18", composer, "Concerto", "Op. 18", "Piano", "C minor") : Map.of();
            case "Paul Dukas" -> identity.contains("sorcerer s apprentice") || identity.contains("apprenti sorcier")
                ? documented("The Sorcerer's Apprentice", composer, "Scherzo", "", "", "") : Map.of();
            case "Jean Sibelius" -> identity.contains("finlandia") && identity.matches(".*\\bop 26\\b.*")
                ? documented("Finlandia, Op. 26", composer, "Symphonic Poem", "Op. 26", "", "") : Map.of();
            case "Nikolai Rimsky-Korsakov" -> identity.contains("scheherazade")
                ? documented("Scheherazade, Op. 35", composer, "Suite", "Op. 35", "", "") : Map.of();
            case "Carl Orff" -> identity.contains("carmina burana")
                ? documented("Carmina Burana", composer, "Cantata", "", "", "") : Map.of();
            default -> Map.of();
        };
    }

    private static Map<Field, String> fourSeasons(String composer, String identity) {
        var season = SEASON.matcher(identity);
        if (!season.find()) return Map.of();
        return switch (season.group(1)) {
            case "spring" -> documented("The Four Seasons – Spring, RV 269", composer, "Concerto", "RV 269", "Violin", "E major");
            case "summer" -> documented("The Four Seasons – Summer, RV 315", composer, "Concerto", "RV 315", "Violin", "G minor");
            case "autumn", "fall" -> documented("The Four Seasons – Autumn, RV 293", composer, "Concerto", "RV 293", "Violin", "F major");
            case "winter" -> documented("The Four Seasons – Winter, RV 297", composer, "Concerto", "RV 297", "Violin", "F minor");
            default -> Map.of();
        };
    }

    private static Map<Field, String> documented(String work, String composer, String form, String opus,
                                                  String instrument, String key) {
        var fields = fromTitle(work, composer, false);
        fields.put(Field.FORM, form);
        if (!opus.isBlank()) fields.put(Field.OPUS, opus);
        if (!instrument.isBlank()) fields.put(Field.INSTRUMENTATION, instrument);
        if (!key.isBlank()) fields.put(Field.WORK_KEY, key);
        return fields;
    }

    private static Map<Field,String> beethoven(String title, String opus, String key, String date, boolean singleMovement) {
        var result = fromTitle(title, "Ludwig van Beethoven", false);
        result.put(Field.OPUS, opus);
        result.put(Field.WORK_KEY, key);
        if (!date.isBlank()) result.put(Field.COMPOSITION_DATE, date);
        result.put(Field.FORM, singleMovement ? "Bagatelle" : ClassicalHints.form(title));
        result.put(Field.INSTRUMENTATION, "Piano");
        if (singleMovement) result.put(Field.KEY, key);
        return result;
    }

    public static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
            .replaceAll("\\p{M}+", "").replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
