package sangitam.desktop.library;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Suggestions for human organization. These labels are not predictions from the audio analyzer. */
public final class TagVocabulary {
    private static final String WORDS = """
        Focus|Study|Reading|Writing|Coding|Work|Relax|Unwind|Meditation|Sleep|Morning|Evening|Night|Travel|Walking|Running|Workout|Dance|Dinner|Background
        |Calm|Gentle|Soft|Quiet|Intense|Powerful|Bright|Dark|Warm|Cool|Airy|Dense|Spacious|Minimal|Rich|Lyrical|Playful|Dramatic|Reflective|Uplifting
        |Low energy|Medium energy|High energy|Slow|Moderate|Fast|Steady|Flowing|Rhythmic|Free rhythm|Acoustic|Electric|Electronic|Instrumental|Vocal|Live|Studio|Solo|Duo|Trio
        |Classical|Jazz|Blues|Rock|Pop|Folk|Country|Soul|Funk|Disco|Ambient|House|Techno|Trance|Hip hop|R&B|Reggae|Metal|Punk|Soundtrack|World|Indian classical|Carnatic|Hindustani
        |Sonata|Symphony|Concerto|Nocturne|Prelude|Fugue|Waltz|Etude|Polonaise|Mazurka|Scherzo|Rondo|Suite|Overture|Requiem|Mass|Cantata|Oratorio|Opera|Ballet|Toccata|Passacaglia|Chaconne|Variations
        |Medieval|Renaissance|Baroque|Classical period|Romantic|Impressionist|Modern|Contemporary|Early music|Chamber music|Orchestral|Choral|Sacred|Secular|Piano|Violin|Viola|Cello|Double bass|Flute
        |Oboe|Clarinet|Bassoon|Trumpet|Horn|Trombone|Tuba|Harp|Guitar|Organ|Harpsichord|Percussion|Saxophone|Sitar|Tabla|Veena|Mridangam|Bansuri|Choir|String quartet
        |Piano trio|String orchestra|Symphony orchestra|Wind ensemble|Brass ensemble|Piano four hands|Piano duet|Sonata cycle|Complete work|Movement|Major key|Minor key|Favorites|To explore|Rediscover|Practice|Reference|Audiophile|Historical recording|New discovery
        """;
    public static final List<String> ALL = Arrays.stream(WORDS.replace("\n", "").split("\\|"))
        .map(String::strip).filter(value -> !value.isEmpty()).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    private TagVocabulary() { }
    public static List<String> matching(String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(tag -> tag.toLowerCase(Locale.ROOT).contains(needle)).toList();
    }
}
