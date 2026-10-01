package taskj2.list;

final class StepOutcome {
    final boolean reachedEnd;
    final Node nextPrev;
    final Node nextCurr;
    final Node nextNext;

    StepOutcome(boolean reachedEnd, Node nextPrev, Node nextCurr, Node nextNext) {
        this.reachedEnd = reachedEnd;
        this.nextPrev = nextPrev;
        this.nextCurr = nextCurr;
        this.nextNext = nextNext;
    }
}
