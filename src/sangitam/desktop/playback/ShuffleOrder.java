package sangitam.desktop.playback;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Random;

/** One shuffle pass over distinct queue entries and the successful playback history of that pass. */
public final class ShuffleOrder<E> {
    private final Random random;
    private final List<E> upcoming = new ArrayList<>();
    private final List<E> history = new ArrayList<>();
    private final Set<E> attempted = new HashSet<>();
    private int head;
    private int cursor = -1;
    private int retrace = -1;
    private E current;

    public ShuffleOrder(Random random) { this.random = random; }

    public void reset(Collection<E> source) {
        upcoming.clear();
        upcoming.addAll(source);
        Collections.shuffle(upcoming, random);
        history.clear();
        attempted.clear();
        head = 0;
        cursor = -1;
        retrace = -1;
        current = null;
    }

    public void reconcile(Collection<E> source) {
        Set<E> eligible = new HashSet<>(source);
        List<E> future = upcoming.subList(head, upcoming.size());
        future.removeIf(entry -> !eligible.contains(entry));
        Set<E> scheduled = new HashSet<>(future);
        for (E entry : source) {
            if (!attempted.contains(entry) && scheduled.add(entry)) upcoming.add(entry);
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            if (!eligible.contains(history.get(i))) removeFromHistory(i);
        }
    }

    public E current() { return current; }

    public E select(E entry) {
        if (entry.equals(current)) return current;
        int index = upcoming.subList(head, upcoming.size()).indexOf(entry);
        if (index >= 0) upcoming.remove(head + index);
        attempted.add(entry);
        retrace = -1;
        current = entry;
        return entry;
    }

    public E next() {
        int forward = (retrace >= 0 ? retrace : cursor) + 1;
        if (forward < history.size()) return retrace(forward);
        if (head == upcoming.size()) return null;
        current = upcoming.get(head++);
        attempted.add(current);
        retrace = -1;
        return current;
    }

    public E previous() {
        int previous = retrace >= 0 ? retrace - 1 : cursor;
        if (retrace < 0 && previous >= 0 && history.get(previous).equals(current)) previous--;
        return previous < 0 ? null : retrace(previous);
    }

    private E retrace(int index) {
        retrace = index;
        current = history.get(index);
        return current;
    }

    public E chooseVisible(int index) {
        if (current != null && index-- == 0) return current;
        int forward = forwardStart();
        if (index < history.size() - forward) return retrace(forward + index);
        return select(upcoming.get(head + index - (history.size() - forward)));
    }

    public boolean started() {
        if (current == null) return false;
        if (retrace >= 0) {
            cursor = retrace;
            retrace = -1;
            return true;
        } else if (cursor < 0 || !current.equals(history.get(cursor))) {
            history.subList(cursor + 1, history.size()).clear();
            history.add(current);
            cursor++;
            return true;
        }
        return false;
    }

    public void failed() {
        if (retrace >= 0) removeFromHistory(retrace);
        current = null;
    }

    private void removeFromHistory(int index) {
        history.remove(index);
        if (index <= cursor) cursor--;
        if (index == retrace) retrace = -1;
        else if (index < retrace) retrace--;
    }

    private int forwardStart() { return (retrace >= 0 ? retrace : cursor) + 1; }

    /** A live view, read on the same thread as the transport. */
    public List<E> visible() {
        return new AbstractList<>() {
            @Override public E get(int index) {
                java.util.Objects.checkIndex(index, size());
                if (current != null && index-- == 0) return current;
                int forward = forwardStart();
                int historyCount = history.size() - forward;
                return index < historyCount ? history.get(forward + index)
                    : upcoming.get(head + index - historyCount);
            }
            @Override public int size() {
                return (current == null ? 0 : 1) + history.size() - forwardStart() + upcoming.size() - head;
            }
        };
    }
}
