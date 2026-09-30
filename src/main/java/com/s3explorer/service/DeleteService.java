package com.s3explorer.service;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.ObjectRef;
import com.s3explorer.model.TransferTask;
import com.s3explorer.util.UserFacingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.List;

/**
 * Deletes exactly the objects the user confirmed, in batches of 1000. Falls back to
 * one-by-one deletes for S3-compatible services that do not implement DeleteObjects.
 */
public class DeleteService implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(DeleteService.class);
    private static final int BATCH = 1000;

    @Override
    public void execute(TransferTask task, TransferContext ctx) {
        List<ObjectRef> refs = task.deleteRefs();
        if (refs == null) {
            throw new UserFacingException("Cannot resume delete.",
                    "Delete operations are not resumed after restarting the application. Delete the items again.");
        }
        S3Connection connection = ctx.connection(task.sourceConnectionId());
        String bucket = task.source().bucket();
        List<String> errors = new ArrayList<>();
        int index = task.deleteIndex();
        task.setTransferred(index);
        while (index < refs.size()) {
            ctx.checkpoint(task);
            List<ObjectRef> batch = refs.subList(index, Math.min(index + BATCH, refs.size()));
            deleteBatch(connection.client(), bucket, batch, errors);
            index += batch.size();
            task.setDeleteIndex(index);
            task.setTransferred(index);
        }
        log.info("Deleted {} object(s) from {} ({} error(s))", refs.size() - errors.size(), bucket, errors.size());
        if (!errors.isEmpty()) {
            throw new UserFacingException("Some objects were not deleted.",
                    errors.size() + " of " + refs.size() + " object(s) could not be deleted. First error: "
                            + errors.get(0));
        }
    }

    private void deleteBatch(S3Client client, String bucket, List<ObjectRef> batch, List<String> errors) {
        List<ObjectIdentifier> ids = batch.stream().map(r -> ObjectIdentifier.builder().key(r.key()).build()).toList();
        try {
            DeleteObjectsResponse response = client.deleteObjects(b -> b.bucket(bucket)
                    .delete(d -> d.quiet(true).objects(ids)));
            for (S3Error e : response.errors()) {
                errors.add(e.key() + ": " + (e.message() == null ? e.code() : e.message()));
            }
        } catch (S3Exception e) {
            int status = e.statusCode();
            if (status == 400 || status == 405 || status == 501) {
                log.info("DeleteObjects not supported by endpoint ({}); deleting individually", status);
                for (ObjectRef ref : batch) {
                    try {
                        client.deleteObject(b -> b.bucket(bucket).key(ref.key()));
                    } catch (S3Exception single) {
                        errors.add(ref.key() + ": " + single.awsErrorDetails().errorMessage());
                    }
                }
            } else {
                throw e;
            }
        }
    }

    @Override
    public String actionDescription(TransferTask task) {
        return "delete objects from " + task.source().bucket();
    }
}
