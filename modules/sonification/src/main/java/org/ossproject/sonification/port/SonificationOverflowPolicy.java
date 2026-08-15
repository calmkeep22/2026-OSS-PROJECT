package org.ossproject.sonification.port;

/** Declares how an asynchronous output adapter behaves when its playback queue is full. */
public enum SonificationOverflowPolicy {
    /** Discards the oldest frame that has not started so the newest graph change is retained. */
    DROP_OLDEST,
    /** Keeps queued frames and rejects or discards the newest request. */
    REJECT_NEWEST,
    /** Blocks the producer until output capacity becomes available. */
    BLOCK_PRODUCER
}
