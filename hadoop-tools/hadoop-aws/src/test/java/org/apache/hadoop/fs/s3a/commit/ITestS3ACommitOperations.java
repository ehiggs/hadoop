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
import java.util.List;

import com.amazonaws.services.s3.model.PartETag;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3a.S3AFileSystem;
import org.apache.hadoop.fs.s3a.commit.files.SinglePendingCommit;
import org.apache.hadoop.fs.s3a.commit.magic.MagicCommitTracker;
import org.apache.hadoop.fs.s3a.commit.magic.MagicS3GuardCommitter;
import org.apache.hadoop.fs.s3a.commit.magic.MagicS3GuardCommitterFactory;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.mapreduce.lib.output.PathOutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.PathOutputCommitterFactory;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;

import static org.apache.hadoop.fs.contract.ContractTestUtils.*;
import static org.apache.hadoop.fs.s3a.S3ATestUtils.*;
import static org.apache.hadoop.fs.s3a.commit.CommitConstants.*;
import static org.apache.hadoop.fs.s3a.commit.CommitUtils.*;
import static org.apache.hadoop.fs.s3a.Constants.*;

/**
 * Test the low-level binding of the S3A FS to the magic commit mechanism,
 * and handling of the commit operations.
 */
public class ITestS3ACommitOperations extends AbstractCommitITest {

  private static final Logger LOG =
      LoggerFactory.getLogger(ITestS3ACommitOperations.class);
  private static final byte[] DATASET = dataset(1000, 'a', 32);

  @Override
  protected Configuration createConfiguration() {
    Configuration conf = super.createConfiguration();
    // use the inconsistent client, but with delays set to a low value,
    // throttling off except when enabled (because not all API calls do retries
    // yet.
    conf.set(PathOutputCommitterFactory.OUTPUTCOMMITTER_FACTORY_CLASS,
        MagicS3GuardCommitterFactory.CLASSNAME);

    // to address recurrent cache problems, disable it
    return conf;
  }

  @Override
  public boolean useInconsistentClient() {
    return true;
  }

  @Override
  public void setup() throws Exception {
    FileSystem.closeAll();
    super.setup();
    verifyIsMagicCommitFS(getFileSystem());
  }

  @Test
  public void testPendingCommitNormalPath() throws Throwable {
    S3AFileSystem fs = getFileSystem();
    PendingCommitFSIntegration integration
        = new PendingCommitFSIntegration(fs, true);
    String filename = "notdelayed.txt";
    Path destFile = methodPath(filename);
    String origKey = fs.pathToKey(destFile);
    DefaultPutTracker tracker = integration.getTracker(destFile, origKey);
    assertFalse("wrong type: " + tracker + " for " + destFile,
        tracker instanceof MagicCommitTracker);
  }

  @Test
  public void testPendingCommitMagicPath() throws Throwable {
    S3AFileSystem fs = getFileSystem();
    PendingCommitFSIntegration integration
        = new PendingCommitFSIntegration(fs, true);
    String filename = "delayed.txt";
    Path destFile = methodPath(filename);
    String origKey = fs.pathToKey(destFile);
    Path pendingPath = makePending(destFile);
    verifyIsMagicCommitPath(fs, pendingPath);
    String pendingPathKey = fs.pathToKey(pendingPath);
    assertTrue("wrong path of " + pendingPathKey,
        pendingPathKey.endsWith(filename));
    final List<String> elements = splitPathToElements(pendingPath);
    assertEquals("splitPathToElements()", filename, lastElement(elements));
    List<String> finalDestination = finalDestination(elements);
    assertEquals("finalDestination()",
        filename,
        lastElement(finalDestination));
    final String destKey = elementsToKey(finalDestination);
    assertEquals("destination key", origKey, destKey);

    DefaultPutTracker tracker = integration.getTracker(pendingPath,
        pendingPathKey);
    assertTrue("wrong type: " + tracker + " for " + pendingPathKey,
        tracker instanceof MagicCommitTracker);
    assertEquals("tracker destination key", origKey, tracker.getDestKey());

    Path pendingSuffixedPath = new Path(pendingPath,
        "part-0000" + PENDING_SUFFIX);
    assertFalse("still a delayed complete path " + pendingSuffixedPath,
        fs.isPendingCommitPath(pendingSuffixedPath));
    Path pendingSet = new Path(pendingPath,
        "part-0000" + PENDINGSET_SUFFIX);
    assertFalse("still a delayed complete path " + pendingSet,
        fs.isPendingCommitPath(pendingSet));
  }

  @Test
  public void testCreateAbortEmptyFile() throws Throwable {
    describe("create then abort an empty file; throttled");
    S3AFileSystem fs = getFileSystem();
    String filename = "empty-abort.txt";
    Path destFile = methodPath(filename);
    Path pendingFilePath = makePending(destFile);
    touch(fs, pendingFilePath);
    assertPathDoesNotExist("pending file was created", pendingFilePath);
    assertPathDoesNotExist("dest file was created", destFile);
    Path pendingDataPath = validatePendingCommitData(filename,
        pendingFilePath);

    CommitOperations actions = newActions();
    // abort,; rethrow on failure
    setThrottling(1.0f, 2);
    LOG.info("Abort call");
    actions.abortAllSinglePendingCommits(pendingDataPath.getParent(), true)
        .maybeRethrow();
    resetFailures();
    assertPathDoesNotExist("pending file not deleted", pendingDataPath);
    assertPathDoesNotExist("dest file was created", destFile);
  }

  private CommitOperations newActions() {
    return new CommitOperations(getFileSystem());
  }

  /**
   * Create a new path which has the same filename as the dest file, but
   * is in a pending directory under the destination dir.
   * @param destFile final destination file
   * @return pending path
   */
  private static Path makePending(Path destFile) {
    return makePendingChild(destFile, destFile.getName());
  }

  private static Path makePendingChild(Path destFile, String name) {
    return new Path(destFile.getParent(),
        MAGIC_DIR_NAME + '/' + name);
  }

  @Test
  public void testCommitEmptyFile() throws Throwable {
    describe("create then commit an empty file");
    createCommitAndVerify("empty-commit.txt", new byte[0]);
  }

  @Test
  public void testCommitSmallFile() throws Throwable {
    describe("create then commit an empty file");
    createCommitAndVerify("small-commit.txt", DATASET);
  }

  @Test
  public void testAbortNonexistentDir() throws Throwable {
    describe("Attempt to abort a directory that does not exist");
    Path destFile = methodPath("testAbortNonexistentPath");
    newActions()
        .abortAllSinglePendingCommits(destFile, true)
        .maybeRethrow();
  }

  @Test
  public void testCommitterFactory() throws Throwable {
    Configuration conf = new Configuration();
    conf.set(PathOutputCommitterFactory.OUTPUTCOMMITTER_FACTORY_CLASS,
        MagicS3GuardCommitterFactory.CLASSNAME);
    PathOutputCommitterFactory factory
        = PathOutputCommitterFactory.getOutputCommitterFactory(conf);
    PathOutputCommitter committer = factory.createOutputCommitter(
        path("testFactory"),
        new TaskAttemptContextImpl(getConfiguration(),
            new TaskAttemptID(new TaskID(), 1)));
    MagicS3GuardCommitter s3a = (MagicS3GuardCommitter) committer;
  }

  @Test
  public void testBaseRelativePath() throws Throwable {
    describe("Test creating file with a __base marker and verify that it ends" +
        " up in where expected");
    Path destDir = methodPath("testBaseRelativePath");
    Path pendingBaseDir = new Path(destDir,
        MAGIC_DIR_NAME + "/child/"
            + BASE_PATH);
    String child = "subdir/child.txt";
    Path pendingChildPath = new Path(pendingBaseDir, child);
    Path expectedDestPath = new Path(destDir, child);
    createFile(getFileSystem(), pendingChildPath, true, DATASET);
    commit("child.txt", pendingChildPath, expectedDestPath, 0, 0);
  }

  private void createCommitAndVerify(String filename, byte[] data)
      throws Exception {
    S3AFileSystem fs = getFileSystem();
    Path destFile = methodPath(filename);
    Path pendingFilePath = makePending(destFile);
    createFile(fs, pendingFilePath, true, data);
    commit(filename, destFile, 0.5f, 0);
    verifyFileContents(fs, destFile, data);
  }

  /**
   * Commit the file, with before and after checks on the dest and pending
   * values.
   * Failures can be set; they'll be reset after the commit.
   * @param filename filename of file
   * @param destFile destination path of file
   * @param throttle probability of commit throttling
   * @param failures failure limit
   * @throws Exception any failure of the operation
   */
  private void commit(String filename,
      Path destFile,
      float throttle,
      int failures) throws Exception {
    commit(filename, makePending(destFile), destFile, throttle, failures);
  }

  /**
   * Commit to a write to {@code pendingFilePath} which is expected to
   * be saved to {@code destFile}.
   * Failures can be set; they'll be reset after the commit.
   * @param pendingFilePath path to write to
   * @param destFile destination to verify
   * @param throttle probability of commit throttling
   * @param failures failure limit
   */
  private void commit(String filename,
      Path pendingFilePath,
      Path destFile,
      float throttle, int failures)
      throws IOException {
    resetFailures();
    assertPathDoesNotExist("pending file was created", pendingFilePath);
    assertPathDoesNotExist("dest file was created", destFile);
    Path pendingDataPath = validatePendingCommitData(filename,
        pendingFilePath);
    SinglePendingCommit commit = SinglePendingCommit.load(getFileSystem(),
        pendingDataPath);
    CommitOperations actions = newActions();
    setThrottling(throttle, failures);
    actions.commitOrFail(commit);
    resetFailures();
    verifyCommitExists(commit);
  }

  /**
   * Verify that the path at the end of a commit exists.
   * This does not validate the size.
   * @param commit commit to verify
   * @throws FileNotFoundException dest doesn't exist
   * @throws ValidationFailure commit arg is invalid
   * @throws IOException invalid commit, IO failure
   */
  private void verifyCommitExists(SinglePendingCommit commit)
      throws FileNotFoundException, ValidationFailure, IOException {
    commit.validate();
    // this will force an existence check
    Path path = getFileSystem().keyToQualifiedPath(commit.getDestinationKey());
    FileStatus status = getFileSystem().getFileStatus(path);
    LOG.debug("Destination entry: {}", status);
    if (!status.isFile()) {
      throw new PathCommitException(path, "Not a file: " + status);
    }
  }

  /**
   * Validate that a pending commit data file exists, load it and validate
   * its contents.
   * @param filename short file name
   * @param pendingFilePath path that the file thinks that it was written to
   * @return the path to the pending data
   * @throws IOException IO problems
   */
  private Path validatePendingCommitData(String filename,
      Path pendingFilePath) throws IOException {
    S3AFileSystem fs = getFileSystem();
    Path pendingDataPath = new Path(pendingFilePath.getParent(),
        filename + PENDING_SUFFIX);
    FileStatus fileStatus = verifyPathExists(fs, "no pending data",
        pendingDataPath);
    assertTrue("No data in " + fileStatus, fileStatus.getLen() > 0);
    String data = read(fs, pendingDataPath);
    LOG.info("Contents of {}: \n{}", pendingDataPath, data);
    // really read it in and parse
    SinglePendingCommit persisted = SinglePendingCommit.serializer()
        .load(fs, pendingDataPath);
    persisted.validate();
    assertTrue("created timestamp wrong in " + persisted,
        persisted.getCreated() > 0);
    assertTrue("saved timestamp wrong in " + persisted,
        persisted.getSaved() > 0);
    List<String> etags = persisted.getEtags();
    assertEquals("etag list " + persisted, 1, etags.size());
    List<PartETag> partList = CommitUtils.toPartEtags(etags);
    assertEquals("part list " + persisted, 1, partList.size());
    return pendingDataPath;
  }

  /**
   * Get a method-relative path.
   * @param filename filename
   * @return new path
   * @throws IOException failure to create/parse the path.
   */
  private Path methodPath(String filename) throws IOException {
    return new Path(path(getMethodName()), filename);
  }

  @Test
  public void testUploadEmptyFile() throws Throwable {
    File tempFile = File.createTempFile("commit", ".txt");
    CommitOperations actions = newActions();
    Path dest = methodPath("testUploadEmptyFile");
    S3AFileSystem fs = getFileSystem();
    fs.delete(dest, false);
    setThrottling(1.0f, 2);

    SinglePendingCommit pendingCommit =
        actions.uploadFileToPendingCommit(tempFile,
            dest, null,
            DEFAULT_MULTIPART_SIZE);
    resetFailures();
    assertPathDoesNotExist("pending commit", dest);
    setThrottling(1.0f, 2);
    actions.commitOrFail(pendingCommit);
    resetFailures();
    FileStatus status = verifyPathExists(fs,
        "uploaded file commit", dest);
    assertEquals("File length in " + status, 0, status.getLen());
  }

  @Test
  public void testUploadSmallFile() throws Throwable {
    File tempFile = File.createTempFile("commit", ".txt");
    String text = "hello, world";
    FileUtils.write(tempFile, text, "UTF-8");
    CommitOperations actions = newActions();
    Path dest = methodPath("testUploadSmallFile");
    S3AFileSystem fs = getFileSystem();
    setThrottling(1.0f, 2);
    SinglePendingCommit pendingCommit =
        actions.uploadFileToPendingCommit(tempFile,
            dest, null,
            DEFAULT_MULTIPART_SIZE);
    resetFailures();
    assertPathDoesNotExist("pending commit", dest);
    setThrottling(1.0f, 2);
    actions.commitOrFail(pendingCommit);
    resetFailures();
    String s = readUTF8(fs, dest, -1);
    assertEquals(text, s);
  }

  @Test(expected = FileNotFoundException.class)
  public void testUploadMissingFile() throws Throwable {
    File tempFile = File.createTempFile("commit", ".txt");
    tempFile.delete();
    CommitOperations actions = newActions();
    Path dest = methodPath("testUploadMissingile");
    setThrottling(1.0f, 2);
    actions.uploadFileToPendingCommit(tempFile, dest, null,
        DEFAULT_MULTIPART_SIZE);
  }

  @Test
  public void testRevertCommit() throws Throwable {
    Path destFile = methodPath("part-0000");
    S3AFileSystem fs = getFileSystem();
    touch(fs, destFile);
    CommitOperations actions = newActions();
    SinglePendingCommit commit = new SinglePendingCommit();
    commit.setDestinationKey(fs.pathToKey(destFile));
    setThrottling(1.0f, 2);
    actions.revertCommit(commit);
    resetFailures();
    assertPathExists("parent of reverted commit", destFile.getParent());
  }

  @Test
  public void testRevertMissingCommit() throws Throwable {
    Path destFile = methodPath("part-0000");
    S3AFileSystem fs = getFileSystem();
    fs.delete(destFile, false);
    CommitOperations actions = newActions();
    SinglePendingCommit commit = new SinglePendingCommit();
    commit.setDestinationKey(fs.pathToKey(destFile));
    setThrottling(1.0f, 2);
    actions.revertCommit(commit);
    assertPathExists("parent of reverted (nonexistent) commit",
        destFile.getParent());
  }

  @Test
  public void testFailuresInAbortListing() throws Throwable {
    CommitOperations actions = newActions();
    Path path = path("testFailuresInAbort");
    getFileSystem().mkdirs(path);
    setThrottling(0.5f);
    LOG.info("Aborting");
    actions.abortPendingUploadsUnderPath(path);
    LOG.info("Abort completed");
    resetFailures();
  }
}
