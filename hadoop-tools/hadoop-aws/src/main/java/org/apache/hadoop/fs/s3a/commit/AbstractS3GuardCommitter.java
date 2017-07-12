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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3a.S3AFileSystem;
import org.apache.hadoop.fs.s3a.commit.files.Pendingset;
import org.apache.hadoop.fs.s3a.commit.files.SinglePendingCommit;
import org.apache.hadoop.fs.s3a.commit.files.SuccessData;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.PathOutputCommitter;
import org.apache.hadoop.net.NetUtils;

import static org.apache.hadoop.fs.s3a.S3AUtils.deleteQuietly;
import static org.apache.hadoop.fs.s3a.commit.CommitConstants.*;
import static org.apache.hadoop.fs.s3a.commit.CommitUtils.*;

/**
 * Abstract base class for s3guard committers; allows for any commonality
 * between different architectures.
 *
 * Although the committer APIs allow for a committer to be created without
 * an output path, this is no supported in this class or its subclasses:
 * a destination must be supplied. It is left to the committer factory
 * to handle the creation of a committer when the destination is unknown.
 *
 * Requiring an output directory simplifies coding and testing.
 */
public abstract class AbstractS3GuardCommitter extends PathOutputCommitter {
  private static final Logger LOG =
      LoggerFactory.getLogger(AbstractS3GuardCommitter.class);

  /**
   * Thread pool for task execution.
   */
  private ExecutorService threadPool;

  /** Underlying commit operations. */
  private CommitActions commitActions;

  /**
   * Final destination of work.
   */
  private Path outputPath;

  /**
   * Role: used in log/text messages.
   */
  private final String role;

  /**
   * This is the directory for all intermediate work: where the output format
   * will write data.
   * <i>This may not be on the final file system</i>
   */
  private Path workPath;

  /** Configuration of the job. */
  private Configuration conf;

  /** Filesystem of {@link #outputPath}. */
  private FileSystem destFS;

  /** The job context. For a task, this can be cast to a TaskContext. */
  private final JobContext jobContext;

  /**
   * Create a committer.
   * @param outputPath the job's output path: MUST NOT be null.
   * @param context the job context
   * @throws IOException on a failure
   */
  private AbstractS3GuardCommitter(
      String role,
      Path outputPath,
      JobContext context) throws IOException {
    Preconditions.checkArgument(outputPath != null, "null output path");
    Preconditions.checkArgument(context != null, "null job context");
    this.jobContext = context;
    this.role = role;
    setConf(context.getConfiguration());
    initOutput(outputPath);
    LOG.debug("{} instantiated for job \"{}\" ID {} with destination {}",
        role, jobName(context), jobIdString(context), outputPath);
    S3AFileSystem fs = getDestS3AFS();
    commitActions = new CommitActions(fs);
  }

 /**
   * Create a committer.
   * @param outputPath the job's output path: MUST NOT be null.
   * @param context the job context
   * @throws IOException on a failure
   */
  protected AbstractS3GuardCommitter(Path outputPath,
      JobContext context) throws IOException {
    this("Job committer " + jobIdString(context), outputPath, context);
  }

  /**
   * Create a committer.
   * This constructor binds the destination directory and configuration, but
   * does not update the work path: That must be calculated by the
   * implementation;
   * It is omitted here to avoid subclass methods being called too early.
   * @param outputPath the job's output path: MUST NOT be null.
   * @param context the task's context
   * @throws IOException on a failure
   */
  protected AbstractS3GuardCommitter(Path outputPath,
      TaskAttemptContext context) throws IOException {
    this("Task committer "+ context.getTaskAttemptID(),
        outputPath, context);
    LOG.debug("{}} instantiated for {} ID {}",
        role, jobName(context), jobIdString(context));
  }

  /**
   * Init the output filesystem and path.
   * TESTING ONLY; allows mock FS to cheat.
   * @param out output path
   * @throws IOException failure to create the FS.
   */
  protected void initOutput(Path out) throws IOException {
    FileSystem fs = getDestination(out, getConf());
    setDestFS(fs);
    setOutputPath(fs.makeQualified(out));
  }

  /**
   * Get the job/task context this committer was instantiated with.
   * @return the context.
   */
  public final JobContext getJobContext() {
    return jobContext;
  }

  /**
   * Final path of output, in the destination FS.
   * @return the path
   */
  public Path getOutputPath() {
    return outputPath;
  }

  /**
   * Set the output path.
   * @param outputPath new value
   */
  protected void setOutputPath(Path outputPath) {
    Preconditions.checkNotNull(outputPath, "Null output path");
    this.outputPath = outputPath;
  }

  /**
   * This is the critical method for {@code FileOutputFormat}; it declares
   * the path for work.
   * @return the working path.
   */
  @Override
  public Path getWorkPath() {
    return workPath;
  }

  /**
   * Set the work path for this committer.
   * @param workPath the work path to use.
   */
  protected void setWorkPath(Path workPath) {
    LOG.debug("Setting work path to {}", workPath);
    this.workPath = workPath;
  }

  public Configuration getConf() {
    return conf;
  }

  protected void setConf(Configuration conf) {
    this.conf = conf;
  }

  /**
   * Get the destination FS, creating it on demand if needed.
   * @return the filesystem; requires the output path to be set up
   * @throws IOException if the FS cannot be instantiated.
   */
  public FileSystem getDestFS() throws IOException {
    if (destFS == null) {
      FileSystem fs = getDestination(outputPath, getConf());
      setDestFS(fs);
    }
    return destFS;
  }

  /**
   * Get the destination as an S3A Filesystem; casting it.
   * @return the dest S3A FS.
   * @throws IOException if the FS cannot be instantiated.
   */
  public S3AFileSystem getDestS3AFS() throws IOException {
    return (S3AFileSystem) getDestFS();
  }

  /**
   * Set the destination FS: the FS of the final output.
   * @param destFS destination FS.
   */
  protected void setDestFS(FileSystem destFS) {
    this.destFS = destFS;
  }

  /**
   * Compute the path where the output of a given job attempt will be placed.
   * @param context the context of the job.  This is used to get the
   * application attempt ID.
   * @return the path to store job attempt data.
   */
  public Path getJobAttemptPath(JobContext context) {
    return getJobAttemptPath(getAppAttemptId(context));
  }

  /**
   * Compute the path where the output of a given job attempt will be placed.
   * @param appAttemptId the ID of the application attempt for this job.
   * @return the path to store job attempt data.
   */
  protected abstract Path getJobAttemptPath(int appAttemptId);

  /**
   * Compute the path where the output of a task attempt is stored until
   * that task is committed. This may be the normal Task attempt path
   * or it may be a subdirectory.
   * The default implementation returns the value of
   * {@link #getBaseTaskAttemptPath(TaskAttemptContext)};
   * subclasses may return different values.
   * @param context the context of the task attempt.
   * @return the path where a task attempt should be stored.
   */
  public Path getTaskAttemptPath(TaskAttemptContext context) {
    return getBaseTaskAttemptPath(context);
  }

  /**
   * Compute the base path where the output of a task attempt is written.
   * This is the path which will be deleted when a task is cleaned up and
   * aborted.

   *
   * @param context the context of the task attempt.
   * @return the path where a task attempt should be stored.
   */
  protected abstract Path getBaseTaskAttemptPath(TaskAttemptContext context);

  /**
   * Get a temporary directory for data. When a task is aborted/cleaned
   * up, the contents of this directory are all deleted.
   * @param context task context
   * @return a path for temporary data.
   */
  public abstract Path getTempTaskAttemptPath(TaskAttemptContext context);

  /**
   * Get the name of this committer.
   * @return the committer name.
   */
  public abstract String getName();

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(
        "AbstractS3GuardCommitter{");
    sb.append("role=").append(role);
    sb.append(", outputPath=").append(getOutputPath());
    sb.append(", workPath=").append(workPath);
    sb.append('}');
    return sb.toString();
  }

  /**
   * Get the destination filesystem from the output path and the configuration.
   * @param out output path
   * @param config job/task config
   * @return the associated FS
   * @throws PathCommitException output path isn't to an S3A FS instance.
   * @throws IOException failure to instantiate the FS.
   */
  protected FileSystem getDestination(Path out, Configuration config)
      throws IOException {
    return getS3AFileSystem(out, config, isMagicFileSystemRequired());
  }

  /**
   * Flag to indicate whether or not the destination filesystem needs
   * to be configured to support the magic path.
   * @return what the requirements of the committer are of the filesystem.
   */
  protected abstract boolean isMagicFileSystemRequired();

  /**
   * Task recovery considered unsupported: Warn and fail.
   * @param taskContext Context of the task whose output is being recovered
   * @throws IOException always.
   */
  @Override
  public void recoverTask(TaskAttemptContext taskContext) throws IOException {
    LOG.warn("Cannot recover task {}", taskContext.getTaskAttemptID());
    throw new PathCommitException(outputPath,
        String.format("Unable to recover task %s",
        taskContext.getTaskAttemptID()));
  }

  /**
   * if the job requires a success marker on a successful job,
   * create the file {@link CommitConstants#SUCCESS_FILE_NAME}.
   *
   * While the classic committers create a 0-byte file, the S3Guard committers
   * PUT up a the contents of a {@link SuccessData} file.
   * @param context job context
   * @param pending the pending commits
   * @throws IOException IO failure
   */
  protected void maybeCreateSuccessMarkerFromCommits(JobContext context,
      List<SinglePendingCommit> pending) throws IOException {
    List<String> filenames = new ArrayList<>(pending.size());
    for (SinglePendingCommit commit : pending) {
      String key = commit.getDestinationKey();
      if (!key.startsWith("/")) {
        // fix up so that FS.makeQualified() sets up the path OK
        key = "/" + key;
      }
      filenames.add(key);
    }
    maybeCreateSuccessMarker(context, filenames);
  }

  /**
   * if the job requires a success marker on a successful job,
   * create the file {@link CommitConstants#SUCCESS_FILE_NAME}.
   *
   * While the classic committers create a 0-byte file, the S3Guard committers
   * PUT up a the contents of a {@link SuccessData} file.
   * @param context job context
   * @param filenames list of filenames.
   * @throws IOException IO failure
   */
  protected void maybeCreateSuccessMarker(JobContext context,
      List<String> filenames)
      throws IOException {
    if (context.getConfiguration().getBoolean(
        CREATE_SUCCESSFUL_JOB_OUTPUT_DIR_MARKER,
        DEFAULT_CREATE_SUCCESSFUL_JOB_DIR_MARKER)) {
      // create a success data structure and then save it
      SuccessData successData = new SuccessData();
      successData.setCommitter(getName());
      successData.setDescription(getRole());
      successData.setHostname(NetUtils.getLocalHostname());
      Date now = new Date();
      successData.setTimestamp(now.getTime());
      successData.setDate(now.toString());
      successData.setFilenames(filenames);
      commitActions.createSuccessMarker(getOutputPath(), successData, true);
    }
  }

  @Override
  public void setupTask(TaskAttemptContext context) throws IOException {
    try (DurationInfo d = new DurationInfo("Setup Task %s",
        context.getTaskAttemptID())) {
      Path taskAttemptPath = getTaskAttemptPath(context);
      FileSystem fs = getTaskAttemptFilesystem(context);
      fs.mkdirs(taskAttemptPath);
    }
  }

  /**
   * Get the task attempt path filesystem. This may not be the same as the
   * final destination FS, and so may not be an S3A FS.
   * @param context task attempt
   * @return the filesystem
   * @throws IOException failure to instantiate
   */
  protected FileSystem getTaskAttemptFilesystem(TaskAttemptContext context)
      throws IOException {
    return getTaskAttemptPath(context).getFileSystem(getConf());
  }

  /**
   * Commit a list of pending uploads.
   * @param context job context
   * @param pending list of pending uploads
   * @throws IOException on any failure
   */
  protected void commitPendingUploads(JobContext context,
      List<SinglePendingCommit> pending) throws IOException {
    if (pending.isEmpty()) {
      LOG.warn("{}: No pending uploads to commit", getRole());
    }
    LOG.debug("{}: committing the output of {} task(s)",
        getRole(), pending.size());
    Tasks.foreach(pending)
        .stopOnFailure().throwFailureWhenFinished()
        .executeWith(buildThreadPool(context))
        .onFailure(
            new Tasks.FailureTask<SinglePendingCommit, IOException>() {
              @Override
              public void run(SinglePendingCommit commit,
                  Exception exception) throws IOException {
                getCommitActions().abortSingleCommit(commit);
              }
            })
        .abortWith(new Tasks.Task<SinglePendingCommit, IOException>() {
          @Override
          public void run(SinglePendingCommit commit) throws IOException {
            getCommitActions().abortSingleCommit(commit);
          }
        })
        .revertWith(new Tasks.Task<SinglePendingCommit, IOException>() {
          @Override
          public void run(SinglePendingCommit commit) throws IOException {
            getCommitActions().revertCommit(commit);
          }
        })
        .run(new Tasks.Task<SinglePendingCommit, IOException>() {
          @Override
          public void run(SinglePendingCommit commit) throws IOException {
            getCommitActions().commitOrFail(commit);
          }
        });
  }

  /**
   * Try to read every pending file and add all results to pending.
   * in the case of a failure to read the file, exceptions are held until all
   * reads have been attempted.
   * @param context job context
   * @param suppressExceptions whether to suppress exceptions.
   * @param fs job attempt fs
   * @param pendingCommitFiles list of files found in the listing scan
   * @return the list of commits
   * @throws IOException on a failure
   */
  protected List<SinglePendingCommit> loadMultiplePendingCommitFiles(
      JobContext context,
      boolean suppressExceptions,
      FileSystem fs,
      FileStatus[] pendingCommitFiles) throws IOException {

    final List<SinglePendingCommit> pending = Collections.synchronizedList(
        Lists.newArrayList());
    Tasks.foreach(pendingCommitFiles)
        .throwFailureWhenFinished(!suppressExceptions)
        .executeWith(buildThreadPool(context))
        .run(new Tasks.Task<FileStatus, IOException>() {
          @Override
          public void run(FileStatus pendingCommitFile) throws IOException {
            Pendingset commits = Pendingset.load(
                fs, pendingCommitFile.getPath());
            pending.addAll(commits.getCommits());
          }
        });
    return pending;
  }

  /**
   * Internal Job commit operation: where the S3 requests are made
   * (potentially in parallel).
   * @param context job context
   * @param pending pending request
   * @throws IOException any failure
   */
  protected void commitJobInternal(JobContext context,
      List<SinglePendingCommit> pending)
      throws IOException {

    boolean threw = true;
    try {
      commitPendingUploads(context, pending);
      threw = false;
    } finally {
      cleanup(context, threw);
    }
  }

  @Override
  public void abortJob(JobContext context, JobStatus.State state)
      throws IOException {
    IOException ex = null;
    String r = getRole();
    try (DurationInfo d = new DurationInfo("%s: aborting job in state %s ",
        r, CommitUtils.jobIdString(context), state)) {
      List<SinglePendingCommit> pending = getPendingUploadsToAbort(context);
      if (!pending.isEmpty()) {
        abortJobInternal(context, pending, false);
      }
    } catch (FileNotFoundException e) {
      // nothing to list
      LOG.debug("No job directory to read uploads from");
    } catch (IOException e) {
      LOG.error("{}: exception when aborting job {} in state {}",
          r, jobIdString(context), state, e);
      ex = e;
    }

    try (DurationInfo d =
             new DurationInfo("%s: aborting all pending commits", r)) {
      int count = getCommitActions()
          .abortPendingUploadsUnderPath(getOutputPath());
      if (count > 0) {
        LOG.warn("{}: deleted {} extra pending upload(s)", r, count);
      }
    } catch (IOException e) {
      ex = ex == null ? e : ex;
    }
    // final cleanup operations
    cleanup(context, ex != null);
    if (ex != null) {
      throw ex;
    }
  }

  /**
   * Clean up any staging directories.
   * @throws IOException IO problem
   */
  public void cleanupStagingDirs() throws IOException {

  }

  /**
   * Get the list of pending uploads for this job attempt, swallowing
   * exceptions.
   * @param context job context
   * @return a list of pending uploads. If an exception was swallowed,
   * then this may not match the actual set of pending operations
   * @throws IOException shouldn't be raised, but retained for compiler
   */
  protected abstract List<SinglePendingCommit> getPendingUploadsToAbort(
      JobContext context)
      throws IOException;

  /**
   * Cleanup the job context, including aborting anything pending.
   * @param context job context
   * @param suppressExceptions should exceptions be suppressed?
   * @throws IOException any failure if exceptions were not suppressed.
   */
  protected void cleanup(JobContext context, boolean suppressExceptions)
      throws IOException {

  }

  @Override
  @SuppressWarnings("deprecation")
  public void cleanupJob(JobContext context) throws IOException {
    String r = getRole();
    String id = jobIdString(context);
    LOG.warn("{}: using deprecated cleanupJob call for {}", r, id);
    try (DurationInfo d = new DurationInfo("%s: cleanup Job %s", r, id)) {
      cleanup(context, true);
    }
  }

  /**
   * Get the commit actions instance.
   * Subclasses may provide a mock version of this.
   * @return the commit actions instance to use for operations.
   */
  protected CommitActions getCommitActions() {
    return commitActions;
  }

  /**
   * For testing: set a new commit action.
   * @param commitActions commit actions instance
   */
  protected void setCommitActions(CommitActions commitActions) {
    this.commitActions = commitActions;
  }

  /**
   * Used in logging and reporting to help disentangle messages.
   * @return the committer's role.
   */
  protected String getRole() {
    return role;
  }

  /**
   * Returns an {@link ExecutorService} for parallel tasks. The number of
   * threads in the thread-pool is set by s3.multipart.committer.num-threads.
   * If num-threads is 0, this will return null;
   *
   * @param context the JobContext for this commit
   * @return an {@link ExecutorService} or null for the number of threads
   */
  protected final synchronized ExecutorService buildThreadPool(JobContext context) {
    if (threadPool == null) {
      int numThreads = context.getConfiguration().getInt(
          FS_S3A_COMMITTER_THREADS,
          DEFAULT_COMMITTER_THREADS);
      LOG.debug("{}: creating thread pool of size {}", getRole(), numThreads);
      if (numThreads > 0) {
        this.threadPool = Executors.newFixedThreadPool(numThreads,
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("s3-committer-pool-%d")
                .build());
      } else {
        return null;
      }
    }
    return threadPool;
  }

  /**
   * Delete the task attempt path without raising any errors.
   * @param context task context
   */
  protected void deleteTaskAttemptPathQuietly(TaskAttemptContext context) {
    Path attemptPath = getBaseTaskAttemptPath(context);
    try {
      FileSystem taskFS = getTaskAttemptFilesystem(context);
      deleteQuietly(taskFS, attemptPath, true);
    } catch (IOException e) {
      LOG.debug("{}: failed to delete task attempt path {}",
          getRole(), attemptPath, e);
    }
  }

  /**
   * The internal job abort operation.
   * @param context job context
   * @param pending list of pending commits
   * @param suppressExceptions should exceptions be suppressed?
   * @throws IOException any IO problem raised when suppressExceptions is false.
   */
  protected void abortJobInternal(JobContext context,
      List<SinglePendingCommit> pending,
      boolean suppressExceptions)
      throws IOException {
    LOG.warn("{}: aborting Job with {} files", getRole(),
        pending == null ? 0 : pending.size());
    boolean threw = true;
    try {
      abortPendingUploads(context, pending, suppressExceptions);
      // at this point, no exceptions were raised
      threw = false;
    } finally {
      cleanup(context, threw || suppressExceptions);
    }
  }

  /**
   * Abort all pending uploads in the list.
   * @param context job context
   * @param pending pending uploads
   * @param suppressExceptions should exceptions be suppressed
   * @throws IOException any exception raised
   */
  protected void abortPendingUploads(JobContext context,
      List<SinglePendingCommit> pending,
      boolean suppressExceptions)
      throws IOException {
    if (pending == null || pending.isEmpty()) {
      LOG.info("{}: no pending commits to abort", getRole());
    } else {
      Tasks.foreach(pending)
          .throwFailureWhenFinished(!suppressExceptions)
          .executeWith(buildThreadPool(context))
          .onFailure(new Tasks.FailureTask<SinglePendingCommit, IOException>() {
            @Override
            public void run(SinglePendingCommit commit,
                Exception exception) throws IOException {
              // TODO: why retry here?
              getCommitActions().abortSingleCommit(commit);
            }
          })
          .run(new Tasks.Task<SinglePendingCommit, IOException>() {
            @Override
            public void run(SinglePendingCommit commit) throws IOException {
              getCommitActions().abortSingleCommit(commit);
            }
          });
    }
  }

}
