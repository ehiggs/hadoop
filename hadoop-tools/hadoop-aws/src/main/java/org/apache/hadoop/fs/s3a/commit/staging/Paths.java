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

package org.apache.hadoop.fs.s3a.commit.staging;

import java.io.IOException;
import java.net.URI;
import java.util.Random;

import com.google.common.base.Objects;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.s3a.commit.Pair;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.security.UserGroupInformation;

import static org.apache.hadoop.fs.s3a.Constants.BUFFER_DIR;
import static org.apache.hadoop.fs.s3a.commit.CommitConstants.*;
import static org.apache.hadoop.fs.s3a.commit.staging.StagingCommitterConstants.*;

/**
 * Path operations for the staging committers.
 */
public final class Paths {

  private Paths() {
  }

  /**
   * Insert the UUID to a path if it is not there already.
   * If there is a trailing "." in the prefix after the last slash, the
   * UUID is inserted before it with a "-" prefix; otherwise appended.
   *
   * Examples:
   * <pre>
   *   /example/part-0000  ==&gt; /example/part-0000-0ab34
   *   /example/part-0001.gz.csv  ==&gt; /example/part-0001-0ab34.gz.csv
   *   /example/part-0002-0abc3.gz.csv  ==&gt; /example/part-0002-0abc3.gz.csv
   * </pre>
   *
   * @param pathStr path as a string
   * @param uuid UUID to append
   * @return new path.
   */
  public static String addUUID(String pathStr, String uuid) {
    // In some cases, Spark will add the UUID to the filename itself.
    if (pathStr.contains(uuid)) {
      return pathStr;
    }

    int dot; // location of the first '.' in the file name
    int lastSlash = pathStr.lastIndexOf('/');
    if (lastSlash >= 0) {
      dot = pathStr.indexOf('.', lastSlash);
    } else {
      dot = pathStr.indexOf('.');
    }

    if (dot >= 0) {
      return pathStr.substring(0, dot) + "-" + uuid + pathStr.substring(dot);
    } else {
      return pathStr + "-" + uuid;
    }
  }

  /**
   * Get the parent path of a string path: everything up to but excluding
   * the last "/" in the path.
   * @param pathStr path as a string
   * @return the parent or null if there is no parent.
   */
  public static String getParent(String pathStr) {
    int lastSlash = pathStr.lastIndexOf('/');
    if (lastSlash >= 0) {
      return pathStr.substring(0, lastSlash);
    }
    return null;
  }

  /**
   * Using {@code URI.relativize()}, build the relative path from the
   * base path to the full path.
   * TODO: test this thoroughly
   * @param basePath base path
   * @param fullPath full path under the base path.
   * @return the relative path
   */
  public static String getRelativePath(Path basePath,
      Path fullPath) {
    //
    // Use URI.create(Path#toString) to avoid URI character escape bugs
    URI relative = URI.create(basePath.toString())
        .relativize(URI.create(fullPath.toString()));
    return relative.getPath();
  }


  /**
   * Varags constructor of paths. Not very efficient.
   * @param parent parent path
   * @param child child entries. "" elements are skipped.
   * @return the full child path.
   */
  public static Path path(Path parent, String... child) {
    Path p = parent;
    for (String c : child) {
      if (!c.isEmpty()) {
        p = new Path(p, c);
      }
    }
    return p;
  }

  /**
   * Get the task attempt temporary directory in the local filesystem.
   * @param conf configuration
   * @param uuid some UUID, such as a job UUID
   * @param attempt attempt ID
   * @return a local task attempt directory.
   * @throws IOException IO problem.
   */
  public static Path getLocalTaskAttemptTempDir(Configuration conf,
      String uuid, TaskAttemptID attempt) throws IOException {
    int taskId = attempt.getTaskID().getId();
    int attemptId = attempt.getId();
    return path(localTemp(conf, taskId, attemptId),
        uuid,
        Integer.toString(getAppAttemptId(conf)),
        attempt.toString());
  }

  /**
   * Try to come up with a good temp directory for different filesystems.
   * @param fs filesystem
   * @param conf configuration
   * @return a path under which temporary work can go.
   */
  public static Path tempDirForStaging(FileSystem fs,
      Configuration conf) {
    Path temp;
    switch (fs.getScheme()) {
    case "file":
      temp = fs.makeQualified(
          new Path(System.getProperty(JAVA_IO_TMPDIR)));
      break;

    case "s3a":
      // the Staging committer may reject this if it doesn't believe S3A
      // is consistent.
      temp = fs.makeQualified(new Path(FILESYSTEM_TEMP_PATH));
      break;

    // here assume that /tmp is valid
    case "hdfs":
    default:
      String pathname = conf.getTrimmed(
          FS_S3A_COMMITTER_TMP_PATH, FILESYSTEM_TEMP_PATH);
      temp = fs.makeQualified(new Path(pathname));
    }
    return temp;
  }

  /**
   * Get the Application Attempt ID for this job.
   * @param conf the config to look in
   * @return the Application Attempt ID for a given job.
   */
  private static int getAppAttemptId(Configuration conf) {
    return conf.getInt(
        MRJobConfig.APPLICATION_ATTEMPT_ID, 0);
  }

  /**
   * Build a temporary path for the multipart upload commit information
   * in the filesystem.
   * @param conf configuration defining default FS.
   * @param uuid uuid of job
   * @return a path which can be used for temporary work
   * @throws IOException on an IO failure.
   */
  public static Path getMultipartUploadCommitsDirectory(Configuration conf,
      String uuid) throws IOException {
    Path userTmp = new Path(tempDirForStaging(FileSystem.get(conf), conf),
        UserGroupInformation.getCurrentUser().getShortUserName());
    Path work = new Path(userTmp, uuid);
    return new Path(work, STAGING_UPLOADS);
  }

  // TODO: verify this is correct, it comes from dse-storage
  private static Path localTemp(Configuration conf, int taskId, int attemptId)
      throws IOException {
    String[] dirs = conf.getStrings(BUFFER_DIR);
    Random rand = new Random(Objects.hashCode(taskId, attemptId));
    String dir = dirs[rand.nextInt(dirs.length)];

    return FileSystem.getLocal(conf).makeQualified(new Path(dir));
  }

  /**
   * path filter.
   */
  static final class HiddenPathFilter implements PathFilter {
    private static final HiddenPathFilter INSTANCE = new HiddenPathFilter();

    public static HiddenPathFilter get() {
      return INSTANCE;
    }

    private HiddenPathFilter() {
    }

    @Override
    public boolean accept(Path path) {
      return !path.getName().startsWith(".")
          && !path.getName().startsWith("_");
    }
  }

}
