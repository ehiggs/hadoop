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

package org.apache.hadoop.fs.s3a;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.InterruptedIOException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Preconditions;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.InvalidRequestException;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryPolicy;
import static org.apache.hadoop.io.retry.RetryPolicies.*;

import static org.apache.hadoop.fs.s3a.Constants.*;

/**
 * The AWS client request retry policy.
 *
 * This uses the retry options in the configuration file to determine retry
 * count and delays for "normal" retries and separately, for throttling;
 * the latter is best handled for longer with an exponential back-off.
 *
 * Those exceptions considered unrecoverable (networking) are failed fast.
 *
 * For non-idempotent operations, only failures due to throttling are retried.
 */
public class S3ARetryPolicy implements RetryPolicy {

  private final RetryPolicy retryPolicy;

  /**
   * Instantiate
   * @param conf configuration to read.
   */
  public S3ARetryPolicy(Configuration conf) {
    Preconditions.checkArgument(conf != null, "Null configuration");

    // base policy from configuration
    RetryPolicy fixedRetries = retryUpToMaximumCountWithFixedSleep(
        conf.getInt(RETRY_LIMIT, RETRY_LIMIT_DEFAULT),
        conf.getTimeDuration(RETRY_INTERVAL,
            RETRY_INTERVAL_DEFAULT,
            TimeUnit.MILLISECONDS),
        TimeUnit.MILLISECONDS);

    // which is wrapped by a rejection of all non-idempotent calls
    RetryPolicy maybeRetry = (e, retries, failovers, idempotent) ->
        idempotent ?
          fixedRetries.shouldRetry(e, retries, failovers, true)
          : RetryAction.FAIL;

    // and a separate policy for throttle requests, which are considered
    // repeatable, even for non-idempotent calls, as the service
    // rejected the call entirely
    RetryPolicy throttlePolicy = retryUpToMaximumCountWithProportionalSleep(
        conf.getInt(RETRY_LIMIT, RETRY_LIMIT_DEFAULT),
        conf.getTimeDuration(RETRY_INTERVAL,
            RETRY_INTERVAL_DEFAULT,
            TimeUnit.MILLISECONDS),
        TimeUnit.MILLISECONDS);

    RetryPolicy fail = RetryPolicies.TRY_ONCE_THEN_FAIL;

    // the policy map maps the exact classname; subclasses do not
    // inherit policies.
    Map<Class<? extends Exception>, RetryPolicy> policyMap = new HashMap<>();

    // failfast exceptions which we consider unrecoverable
    policyMap.put(UnknownHostException.class, fail);
    policyMap.put(NoRouteToHostException.class, fail);
    policyMap.put(InterruptedException.class, fail);
    // note this does not pick up subclasses (like socket timeout)
    policyMap.put(InterruptedIOException.class, fail);
    policyMap.put(AWSBadRequestException.class, fail);
    policyMap.put(AWSRedirectException.class, fail);
    policyMap.put(FileNotFoundException.class, fail);
    policyMap.put(EOFException.class, fail);
    policyMap.put(InvalidRequestException.class, fail);

    // throttled requests are can be retried, always
    policyMap.put(AWSServiceThrottledException.class, throttlePolicy);

    // other operations
    policyMap.put(AWSClientIOException.class, maybeRetry);
    policyMap.put(AWSServiceIOException.class, maybeRetry);
    policyMap.put(AWSS3IOException.class, maybeRetry);
    policyMap.put(AWSServiceThrottledException.class, maybeRetry);
    retryPolicy = retryByException(maybeRetry, policyMap);
  }

  @Override
  public RetryAction shouldRetry(Exception e,
      int retries,
      int failovers,
      boolean idempotent) throws Exception {
     return retryPolicy.shouldRetry(e, retries, failovers, idempotent);
  }

}
