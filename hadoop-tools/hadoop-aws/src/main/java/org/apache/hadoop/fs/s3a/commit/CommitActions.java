/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a.commit;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.s3a.S3AFileSystem;
import org.apache.hadoop.fs.s3a.S3AInstrumentation;
import org.apache.hadoop.fs.s3a.WriteOperationHelper;
import org.apache.hadoop.fs.s3a.commit.files.Pendingset;
import org.apache.hadoop.fs.s3a.commit.files.SinglePendingCommit;
import org.apache.hadoop.fs.s3a.commit.files.SuccessData;

import static org.apache.hadoop.fs.s3a.S3AUtils.deleteQuietly;
import static org.apache.hadoop.fs.s3a.commit.CommitConstants.*;
import static org.apache.hadoop.fs.s3a.Constants.*;

/**
 * The implementation of the various actions a committer needs.
 * This doesn't implement the protocol/binding to a specific execution engine,
 * just the operations needed to to build one.
 */
public class CommitActions {
  private static final Logger LOG = LoggerFactory.getLogger(
      CommitActions.class);

  private final S3AFileSystem fs;

  /** Statistics. */
  private final S3AInstrumentation.CommitterStatistics statistics;

  /**
   * Instantiate.
   * @param fs FS to bind to
   */
  public CommitActions(S3AFileSystem fs) {
    Preconditions.checkArgument(fs != null, "null fs");
    this.fs = fs;
    statistics = fs.newCommitterStatistics();
  }

  @Override
  public String toString() {
    return "CommitActions{" + fs.getUri() + '}';
  }

  /**
   * Create a new {@link WriteOperationHelper} for working with the destination.
   * @param destKey destination key
   * @return a new instance
   */
  private WriteOperationHelper createWriter(String destKey) {
    return fs.createWriteOperationHelper(destKey);
  }


  /** @return statistics. */
  protected S3AInstrumentation.CommitterStatistics getStatistics() {
    return statistics;
  }

  /**
   * Commit the operation, throwing an exception on any failure.
   * @param commit commit to execute
   * @throws IOException on a failure
   */
  public void commitOrFail(SinglePendingCommit commit) throws IOException {
    commit(commit, commit.getFilename()).maybeRethrow();
  }

  /**
   * Commit a single pending commit; exceptions are caught
   * and converted to an outcome.
   * @param commit entry to commit
   * @param origin origin path/string for outcome text
   * @return the outcome
   */
  public MaybeIOE commit(SinglePendingCommit commit, String origin) {
    LOG.debug("Committing single commit {}", commit);
    MaybeIOE outcome;
    String destKey = "unknown destination";
    try {
      commit.validate();
      destKey = commit.getDestinationKey();
      // finalize the commit
      createWriter(destKey).finalizeMultipartUpload(destKey,
          commit.getUploadId(),
          CommitUtils.toPartEtags(commit.getEtags()),
          commit.getLength());
      LOG.debug("Successful commit");
      outcome = new MaybeIOE();
    } catch (IOException e) {
      String msg = String.format("Failed to commit upload against %s: %s",
          destKey, e);
      LOG.warn(msg, e);
      outcome = new MaybeIOE(e);
    } catch (Exception e) {
      String msg = String.format("Failed to commit upload against %s," +
          " described in %s: %s", destKey, origin, e);
      LOG.warn(msg, e);
      outcome = new MaybeIOE(new PathCommitException(origin, msg, e));
    }
    if (outcome.hasException()) {
      statistics.commitFailed();
    } else {
      statistics.commitCompleted(commit.getLength());
    }
    return outcome;
  }

  /**
   * Locate all files with the pending suffix under a directory.
   * @param pendingDir directory
   * @param recursive recursive listing?
   * @return the list of all located entries
   * @throws IOException if there is a problem listing the path.
   */
  public List<LocatedFileStatus> locateAllSinglePendingCommits(
      Path pendingDir,
      boolean recursive) throws IOException {
    final List<LocatedFileStatus> result = new ArrayList<>();
    FileStatus fileStatus = fs.getFileStatus(pendingDir);
    if (!fileStatus.isDirectory()) {
      throw new PathCommitException(pendingDir,
          "Not a directory : " + fileStatus);
    }
    RemoteIterator<LocatedFileStatus> pendingFiles
        = fs.listFiles(pendingDir, recursive);
    if (!pendingFiles.hasNext()) {
      LOG.info("No files to commit under {}", pendingDir);
    }
    while (pendingFiles.hasNext()) {
      LocatedFileStatus next = pendingFiles.next();
      if (next.getPath().getName().endsWith(
          CommitConstants.PENDING_SUFFIX) && next.isFile()) {
        result.add(next);
      }
    }
    return result;
  }

  /**
   * Load all single pending commits in the directory.
   * All load failures are logged and then added to list of files which would
   * not load.
   * @param pendingDir directory containing commits
   * @param recursive do a recursive scan?
   * @return tuple of loaded entries and those pending files which would
   * not load/validate.
   * @throws IOException on a failure to list the files.
   */
  public Pair<Pendingset,
      List<Pair<LocatedFileStatus, IOException>>>
      loadSinglePendingCommits(
          Path pendingDir,
          boolean recursive) throws IOException {
    List<LocatedFileStatus> statusList = locateAllSinglePendingCommits(
        pendingDir, recursive);
    Pendingset commits = new Pendingset(
        statusList.size());
    List<Pair<LocatedFileStatus, IOException>> failures = new ArrayList<>(1);
    for (LocatedFileStatus status : statusList) {
      try {
        commits.add(SinglePendingCommit.load(fs, status.getPath()));
      } catch (IOException e) {
        LOG.warn("Failed to load commit file {}", status.getPath(), e);
        failures.add(new Pair<>(status, e));
      }
    }
    return Pair.of(commits, failures);
  }

  /**
   * Convert any exception to an IOE, if needed.
   * @param key key to use in a path exception
   * @param ex exception
   * @return an IOE, either the passed in value or a new one wrapping the other
   * exception.
   */
  public IOException makeIOE(String key, Exception ex) {
    return ex instanceof IOException ? (IOException) ex
        : new PathCommitException(key, ex.toString(), ex);
  }

  /**
   * Abort the multipart commit supplied. This is the lower level operation
   * which doesn't generate an outcome, instead raising an exception.
   * @param commit pending commit to abort
   * @throws IOException on any failure
   */
  public void abortSingleCommit(SinglePendingCommit commit)
      throws IOException {
    String destKey = commit.getDestinationKey();
    String origin = commit.getFilename() !=null ?
        (" defined in " + commit.getFilename())
        : "";
    String uploadId = commit.getUploadId();
    LOG.info("Aborting commit to object {}{}", destKey, origin);
    try {
      abortMultipartCommit(destKey, uploadId);
    } finally {
      statistics.commitAborted();
    }
  }

  /**
   * Create an {@code AbortMultipartUpload} request and POST it to S3.
   * @param destKey destination key
   * @param uploadId upload to cancel
   * @throws IOException on any failure
   */
  public void abortMultipartCommit(String destKey, String uploadId)
      throws IOException {
    try {
      createWriter(destKey).abortMultipartCommit(destKey, uploadId);
    } finally {
      statistics.commitAborted();
    }
  }

  /**
   * Enumerate all pending files in a dir/tree, abort.
   * @param pendingDir directory of pending operations
   * @param recursive recurse?
   * @return the outcome of all the abort operations
   * @throws IOException if there is a problem listing the path.
   */
  public MaybeIOE abortAllSinglePendingCommits(Path pendingDir,
      boolean recursive)
      throws IOException {
    Preconditions.checkArgument(pendingDir != null, "null pendingDir");
    RemoteIterator<LocatedFileStatus> pendingFiles;
    try {
      pendingFiles = fs.listFiles(pendingDir, recursive);
    } catch (FileNotFoundException e) {
      LOG.info("No directory to abort {}", pendingDir);
      return new MaybeIOE();
    }
    MaybeIOE outcome = null;
    if (!pendingFiles.hasNext()) {
      LOG.debug("No files to abort under {}", pendingDir);
    }
    while (pendingFiles.hasNext()) {
      Path pendingFile = pendingFiles.next().getPath();
      if (pendingFile.getName().endsWith(CommitConstants.PENDING_SUFFIX)) {
        try {
          abortSingleCommit(SinglePendingCommit.load(fs, pendingFile));
        } catch (FileNotFoundException e){
          LOG.debug("listed file already deleted: {}", pendingFile);
        } catch (IOException | IllegalArgumentException e){
          if (outcome == null) {
            outcome = new MaybeIOE(makeIOE(pendingFile.toString(), e));
          }
        } finally{
          deleteQuietly(fs, pendingFile, false);
        }
      }
    }
    return outcome == null ? new MaybeIOE(): outcome;
  }

  /**
   * Abort all pending uploads to the destination FS under a path.
   * @param dest destination path
   * @return a count of the number of uploads aborted.
   * @throws IOException IO failure
   */
  public int abortPendingUploadsUnderPath(Path dest) throws IOException {
    String destKey = fs.pathToKey(dest);
    return createWriter(destKey).abortMultipartUploadsUnderPath(destKey);
  }

  /**
   * Save the success data to the {@code _SUCCESS} file.
   * @param outputPath output directory
   * @param successData success data to save.
   * @param addMetrics should the FS metrics be added?
   * @throws IOException IO problem
   */
  public void createSuccessMarker(Path outputPath,
      SuccessData successData,
      boolean addMetrics)
      throws IOException {
    Preconditions.checkArgument(outputPath != null, "null outputPath");

    if (addMetrics) {
      addFileSystemStatistics(successData.getMetrics());
    }
    // add any diagnostics
    Configuration conf = fs.getConf();
    successData.addDiagnostic(S3_METADATA_STORE_IMPL,
        conf.getTrimmed(S3_METADATA_STORE_IMPL, "" ));
    successData.addDiagnostic(METADATASTORE_AUTHORITATIVE,
        conf.getTrimmed(METADATASTORE_AUTHORITATIVE, "false" ));
    successData.addDiagnostic(MAGIC_COMMITTER_ENABLED,
        conf.getTrimmed(MAGIC_COMMITTER_ENABLED, "false" ));

    // now write
    Path markerPath = new Path(outputPath, SUCCESS_FILE_NAME);
    LOG.debug("Touching success marker for job {}: {}", markerPath,
        successData);
    successData.save(fs, markerPath, true);
  }

  /**
   * Revert a pending commit by deleting the destination.
   * @param commit pending commit
   * @throws IOException failure
   */
  public void revertCommit(SinglePendingCommit commit) throws IOException {
    LOG.warn("Revert {}", commit);
    try {
      createWriter(commit.getDestinationKey())
          .revertCommit(commit.getDestinationKey());
    } finally {
      statistics.commitReverted();
    }
  }

  /**
   * Upload all the data in the local file, returning the information
   * needed to commit the work.
   * @param localFile local file (be  a file)
   * @param destPath destination path
   * @param partition partition/subdir. Not used
   * @param uploadPartSize size of upload
   * @return a pending upload entry
   * @return the commit data
   * @throws IOException failure
   */
  public SinglePendingCommit uploadFileToPendingCommit(File localFile,
      Path destPath,
      String partition,
      long uploadPartSize)
      throws IOException {

    LOG.debug("Initiating multipart upload from {} to {}",
        localFile, destPath);
    Preconditions.checkArgument(destPath != null);
    if (!localFile.isFile()) {
      throw new FileNotFoundException("Not a file: " + localFile);
    }
    String destURI = destPath.toString();
    String key = fs.pathToKey(destPath);
    WriteOperationHelper writer = createWriter(key);
    String uploadId = null;

    boolean threw = true;
    try {
      statistics.commitCreated();
      uploadId = writer.initiateMultiPartUpload();
      long length = localFile.length();

      SinglePendingCommit commitData = new SinglePendingCommit();
      commitData.setDestinationKey(key);
      commitData.setBucket(fs.getBucket());
      commitData.touch(System.currentTimeMillis());
      commitData.setUploadId(uploadId);
      commitData.setUri(destURI);
      commitData.setText(partition != null ? "partition: " + partition : "");
      commitData.setLength(length);

      long offset = 0;
      long numParts = (length / uploadPartSize +
                           ((length % uploadPartSize) > 0 ? 1 : 0));
      // always write one part, even if it is just an empty one
      if (numParts == 0) {
        numParts = 1;
      }

      List<PartETag> parts = new ArrayList<>((int) numParts);

      LOG.debug("File size is {}, number of parts to upload = {}",
          length, numParts);
      for (int partNumber = 1; partNumber <= numParts; partNumber += 1) {
        long size = Math.min(length - offset, uploadPartSize);
        UploadPartRequest part;
        part = writer.newUploadPartRequest(
            uploadId,
            partNumber,
            (int) size,
            null,
            localFile,
            offset);
        part.setLastPart(partNumber == numParts);
        UploadPartResult partResult = writer.uploadPart(part);
        offset += uploadPartSize;
        parts.add(partResult.getPartETag());
      }

      commitData.bindCommitData(parts);
      threw = false;
      return commitData;
    } finally {
      if (threw && uploadId != null) {
        statistics.commitAborted();
        try {
          abortMultipartCommit(key, uploadId);
        } catch (IOException e) {
          LOG.error("Failed to abort upload {} to {}", uploadId, key, e);
        }
      }
    }
  }

  /**
   * Add the filesystem statistics to the map; overwriting anything
   * with the same name.
   * @param dest destination map
   */
  public void addFileSystemStatistics(Map<String, Long> dest) {
    dest.putAll(fs.getInstrumentation().toMap());
  }

  /**
   * Note that a task has completed.
   * @param success success flag
   */
  public void taskCompleted(boolean success) {
    statistics.taskCompleted(success);
  }

  /**
   * Note that a job has completed.
   * @param success success flag
   */
  public void jobCompleted(boolean success) {
    statistics.jobCompleted(success);
  }

  /**
   * A holder for a possible IOException; the call {@link #maybeRethrow()}
   * will throw any exception passed into the constructor, and be a no-op
   * if none was.
   */
  public static class MaybeIOE {
    private final IOException exception;

    /**
     * Construct without an exception.
     */
    public MaybeIOE() {
      this.exception = null;
    }

    /**
     * Construct with an exception.
     * @param exception exception
     */
    public MaybeIOE(IOException exception) {
      this.exception = exception;
    }

    /**
     * Get any exception.
     * @return the exception.
     */
    public IOException getException() {
      return exception;
    }

    /**
     * Is there an exception in this class?
     * @return true if there is an exception
     */
    public boolean hasException() {
      return exception != null;
    }

    /**
     * Rethrow any exception.
     * @throws IOException the exception field, if non-null.
     */
    public void maybeRethrow() throws IOException {
      if (exception != null) {
        throw exception;
      }
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder("MaybeIOE{");
      sb.append(hasException() ? exception : "");
      sb.append('}');
      return sb.toString();
    }
  }

}
