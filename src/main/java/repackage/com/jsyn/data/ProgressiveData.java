package repackage.com.jsyn.data;

/** Data produced asynchronously. These methods must never block the synthesis thread. */
public interface ProgressiveData extends SequentialData {
    boolean isFrameAvailable(int frame);
    boolean isComplete();
}
