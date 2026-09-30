package com.s3explorer.service;

import com.s3explorer.model.TransferTask;

/** Executes one kind of {@link TransferTask}. */
public interface TaskHandler {

    /** Runs (or resumes) the task. Throws {@link TransferSignal} when paused or cancelled. */
    void execute(TransferTask task, TransferContext context) throws Exception;

    /** Releases partial state after cancellation (abort multipart uploads, delete temp files). */
    default void cleanupCancelled(TransferTask task, TransferContext context) {
    }

    /** Phrase for error messages, following "permission to". */
    String actionDescription(TransferTask task);
}
