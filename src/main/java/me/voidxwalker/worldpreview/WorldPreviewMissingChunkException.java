package me.voidxwalker.worldpreview;

public class WorldPreviewMissingChunkException extends RuntimeException {
    public static final WorldPreviewMissingChunkException INSTANCE = new WorldPreviewMissingChunkException();

    private WorldPreviewMissingChunkException() {
        this.setStackTrace(new StackTraceElement[0]);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        this.setStackTrace(new StackTraceElement[0]);
        return this;
    }
}
