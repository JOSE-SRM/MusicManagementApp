package sangitam.desktop.ui;

/** Time-based easing; a busy EDT advances to the correct position rather than stretching the animation. */
record WidthTransition(int start,int target,long started,long duration) {
    int at(long now){double t=Math.max(0,Math.min(1,(now-started)/(double)duration));double eased=t*t*(3-2*t);return(int)Math.round(start+(target-start)*eased);}
    boolean finished(long now){return now-started>=duration;}
}
