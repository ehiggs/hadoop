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

import com.amazonaws.services.s3.AmazonS3;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import java.io.IOException;

class PartitionedCommitterForTesting extends
    PartitionedStagingCommitter {
  private final AmazonS3 mockClient;

  public PartitionedCommitterForTesting(Path outputPath,
      TaskAttemptContext context, AmazonS3 mockClient) throws IOException {
    super(outputPath, context);
    this.mockClient = mockClient;
  }

  public PartitionedCommitterForTesting(Path outputPath,
      JobContext context,
      AmazonS3 mockClient) throws IOException {
    super(outputPath, context);
    this.mockClient = mockClient;
  }

  @Override
  protected void initOutput(Path out) throws IOException {
    super.initOutput(out);
    setOutputPath(out);
  }

  @Override
  protected AmazonS3 getClient(Path path, Configuration conf) {
    return mockClient;
  }

  /**
   * Returns the mock FS without checking FS type.
   * @param out output path
   * @param conf job/task config
   * @return a filesystem.
   * @throws IOException
   */
  @Override
  protected FileSystem getDestination(Path out, Configuration conf)
      throws IOException {
    return out.getFileSystem(conf);
  }

}
